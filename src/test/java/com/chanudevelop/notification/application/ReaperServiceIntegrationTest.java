package com.chanudevelop.notification.application;

import com.chanudevelop.notification.NotificationApplication;
import com.chanudevelop.notification.TestcontainersConfiguration;
import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.NotificationStatus;
import com.chanudevelop.notification.domain.NotificationType;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 Stuck Reaper 통합 테스트.
 *
 * <p>application-test.yaml에서 worker/retry/reaper 워커는 모두 비활성화 (race condition 차단).
 * ReaperService 메서드를 직접 호출해 한 사이클 동작을 시뮬레이션한다.
 *
 * <p>Stuck 시뮬레이션:
 * 정상 도메인 메서드 시퀀스(create → startProcessing)로 PROCESSING 상태 알림을 만든 후,
 * reflection으로 {@code claimed_at}을 과거 시각으로 강제 설정해 "오래 갇힌 상태"를 재현.
 *
 * <p>본 테스트의 stuck threshold는 1초로 매우 짧게 설정 ({@code @TestPropertySource}) 해서
 * reflection 없이 sleep도 가능하지만, reflection 방식이 더 빠르고 결정적.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>임계값 초과 PROCESSING 알림 → recoverOne → PENDING + worker_id/claimed_at null</li>
 *   <li>임계값 미만 PROCESSING 알림은 pollStuck에 안 잡힘 (정상 워커 보호)</li>
 *   <li>다른 상태(PENDING/SENT/FAILED/DEAD_LETTER)는 pollStuck에 안 잡힘</li>
 *   <li>race condition: recoverOne 호출 시 이미 SENT/FAILED로 전이된 알림은 skip</li>
 *   <li>recoverFromStuck은 retry_count를 변경하지 않음 (정책 검증)</li>
 * </ul>
 */
@SpringBootTest(classes = NotificationApplication.class)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "notification.reaper.stuck-threshold-seconds=1")
class ReaperServiceIntegrationTest {

    @Autowired
    private ReaperService reaperService;

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("임계값 이상 갇힌 PROCESSING 알림은 PENDING으로 복구되고 worker_id/claimed_at이 null로 리셋된다")
    void recoverOne_stuck_processing_back_to_pending() throws Exception {
        Notification stuck = createProcessingNotification("worker-dead");
        forceClaimedAtToPast(stuck, 10);   // 10초 전 claim (threshold 1초 초과)
        notificationRepository.saveAndFlush(stuck);

        List<UUID> ids = reaperService.pollStuck();
        assertThat(ids).hasSize(1);
        reaperService.recoverOne(ids.get(0));

        Notification reloaded = notificationRepository.findById(ids.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(reloaded.getWorkerId()).isNull();
        assertThat(reloaded.getClaimedAt()).isNull();
    }

    @Test
    @DisplayName("임계값 미만 PROCESSING 알림은 pollStuck에 안 잡힌다 (정상 워커 보호)")
    void pollStuck_skips_recent_processing() throws Exception {
        Notification recentlyClaimed = createProcessingNotification("worker-alive");
        // 갓 PROCESSING으로 잡힌 상태 — claimed_at이 현재시각 근처
        notificationRepository.saveAndFlush(recentlyClaimed);

        List<UUID> ids = reaperService.pollStuck();
        assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("pollStuck은 PROCESSING 상태만 잡고 PENDING/SENT/FAILED/DEAD_LETTER는 건드리지 않는다")
    void pollStuck_picks_only_processing() throws Exception {
        Notification pending = freshNotification();

        Notification stuckProcessing = createProcessingNotification("worker-dead");
        forceClaimedAtToPast(stuckProcessing, 10);

        Notification sent = createProcessingNotification("worker-alive");
        sent.markAsSent("title", "body");

        Notification failed = freshNotification();
        failed.startProcessing("worker-x");
        failed.markAsFailed("test");

        Notification dead = freshNotification();
        dead.startProcessing("worker-x");
        dead.markAsFailed("test");
        dead.moveToDeadLetter();

        notificationRepository.saveAllAndFlush(List.of(pending, stuckProcessing, sent, failed, dead));

        List<UUID> ids = reaperService.pollStuck();

        assertThat(ids).hasSize(1);
        assertThat(ids.get(0)).isEqualTo(stuckProcessing.getId());
    }

    @Test
    @DisplayName("race condition: pollStuck 이후 recoverOne 호출 전에 알림이 SENT로 전이되어 있으면 skip")
    void recoverOne_skips_if_already_transitioned() throws Exception {
        Notification stuck = createProcessingNotification("worker-dead");
        forceClaimedAtToPast(stuck, 10);
        notificationRepository.saveAndFlush(stuck);

        List<UUID> ids = reaperService.pollStuck();
        assertThat(ids).hasSize(1);

        // pollStuck과 recoverOne 사이에 정상 워커가 작업 완료 시뮬레이션
        Notification middle = notificationRepository.findById(ids.get(0)).orElseThrow();
        middle.markAsSent("late-title", "late-body");
        notificationRepository.saveAndFlush(middle);

        // recoverOne 호출 — 이미 SENT라 skip되어야 함 (예외 던지지 않음)
        reaperService.recoverOne(ids.get(0));

        Notification reloaded = notificationRepository.findById(ids.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getTitle()).isEqualTo("late-title");
    }

    @Test
    @DisplayName("recoverFromStuck은 retry_count를 변경하지 않는다 (Stuck은 인프라 사고이지 외부 시스템 사고가 아니므로)")
    void recover_does_not_increment_retry_count() throws Exception {
        Notification stuck = freshNotification();
        stuck.startProcessing("worker-1");
        stuck.markAsFailed("first failure");        // retryCount=1
        stuck.scheduleRetry(LocalDateTime.now());   // FAILED → PENDING
        stuck.startProcessing("worker-2");           // PENDING → PROCESSING, retryCount 여전히 1
        forceClaimedAtToPast(stuck, 10);
        notificationRepository.saveAndFlush(stuck);

        List<UUID> ids = reaperService.pollStuck();
        reaperService.recoverOne(ids.get(0));

        Notification reloaded = notificationRepository.findById(ids.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(reloaded.getRetryCount()).isEqualTo(1);   // 변경 X
    }

    // ===== 헬퍼 =====

    private Notification freshNotification() {
        return Notification.create(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP,
                "ref-" + UUID.randomUUID().toString().substring(0, 8),
                Map.of("studentName", "테스트", "courseName", "Stuck")
        );
    }

    private Notification createProcessingNotification(String workerId) {
        Notification n = freshNotification();
        n.startProcessing(workerId);
        return n;
    }

    /**
     * 통합 테스트용 — claimed_at을 과거 시각으로 강제 설정.
     *
     * <p>운영 코드에는 setter가 없으므로(Rich Domain Model) reflection 사용.
     * 정상 흐름에선 결코 일어나지 않는 "워커가 N초 전에 잡고 죽은 상태"를 재현하기 위한
     * 테스트 한정 trick.
     */
    private void forceClaimedAtToPast(Notification n, long secondsAgo) throws Exception {
        Field field = Notification.class.getDeclaredField("claimedAt");
        field.setAccessible(true);
        field.set(n, LocalDateTime.now().minusSeconds(secondsAgo));
    }
}
