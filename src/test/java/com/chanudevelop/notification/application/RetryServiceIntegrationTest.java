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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 재시도 + 백오프 통합 테스트.
 *
 * <p>워커(@Scheduled)는 application-test.yaml에서 비활성화하고,
 * RetryService 메서드를 직접 호출해 한 사이클 동작을 시뮬레이션한다.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>FAILED + retryCount &lt; maxRetry → retryOne → PENDING + nextAttemptAt=미래</li>
 *   <li>FAILED + retryCount &gt;= maxRetry → retryOne → DEAD_LETTER</li>
 *   <li>pollFailed가 SKIP LOCKED로 FAILED만 잡음</li>
 *   <li>pollFailed가 PENDING/PROCESSING/SENT/DEAD_LETTER는 안 잡음</li>
 *   <li>scheduleRetry 후 nextAttemptAt이 최소 base-delay (10초) 이상 미래</li>
 * </ul>
 */
@SpringBootTest(classes = NotificationApplication.class)
@Import(TestcontainersConfiguration.class)
class RetryServiceIntegrationTest {

    @Autowired
    private RetryService retryService;

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("retryCount < maxRetry인 FAILED 알림은 PENDING으로 되돌려지고 nextAttemptAt이 미래로 갱신된다")
    void retryOne_retriable_failed_goes_back_to_pending() {
        Notification failed = createFailedNotification(2);  // retryCount=2 < maxRetry=5
        notificationRepository.saveAndFlush(failed);

        LocalDateTime beforeRetry = LocalDateTime.now();

        List<UUID> ids = retryService.pollFailed();
        assertThat(ids).hasSize(1);
        retryService.retryOne(ids.get(0));

        Notification reloaded = notificationRepository.findById(ids.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(reloaded.getNextAttemptAt()).isAfter(beforeRetry.plusSeconds(9));  // 최소 base=10s (jitter 0 가능)
        assertThat(reloaded.getWorkerId()).isNull();   // scheduleRetry가 null 초기화
        assertThat(reloaded.getClaimedAt()).isNull();
    }

    @Test
    @DisplayName("retryCount >= maxRetry인 FAILED 알림은 DEAD_LETTER로 전이된다")
    void retryOne_max_exceeded_goes_to_dead_letter() {
        Notification failed = createFailedNotification(5);  // retryCount=5 == maxRetry=5
        notificationRepository.saveAndFlush(failed);

        List<UUID> ids = retryService.pollFailed();
        retryService.retryOne(ids.get(0));

        Notification reloaded = notificationRepository.findById(ids.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(reloaded.getRetryCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("pollFailed는 FAILED 상태만 잡고 다른 상태(PENDING/PROCESSING/SENT/DEAD_LETTER)는 건드리지 않는다")
    void pollFailed_picks_only_failed_state() {
        // 5가지 상태 알림 각각 1건씩 — FAILED만 잡혀야 함
        Notification pending = freshNotification();
        Notification processing = freshNotification();
        processing.startProcessing("worker-test");
        Notification sent = freshNotification();
        sent.startProcessing("worker-test");
        sent.markAsSent("title", "body");
        Notification failed = createFailedNotification(1);
        Notification dead = createFailedNotification(5);
        dead.moveToDeadLetter();

        notificationRepository.saveAllAndFlush(List.of(pending, processing, sent, failed, dead));

        List<UUID> ids = retryService.pollFailed();

        assertThat(ids).hasSize(1);
        assertThat(ids.get(0)).isEqualTo(failed.getId());
    }

    @Test
    @DisplayName("pollFailed는 빈 결과여도 정상 동작한다")
    void pollFailed_empty_returns_empty_list() {
        List<UUID> ids = retryService.pollFailed();
        assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("재시도 후 다시 실패하면 retryCount가 누적된다 (백오프 증가 검증의 토대)")
    void multiple_failures_accumulate_retry_count() {
        Notification failed = createFailedNotification(3);  // retryCount=3
        notificationRepository.saveAndFlush(failed);

        List<UUID> ids = retryService.pollFailed();
        retryService.retryOne(ids.get(0));

        Notification reloaded = notificationRepository.findById(ids.get(0)).orElseThrow();
        // scheduleRetry는 retryCount 변경 안 함 — 단지 PENDING으로 되돌리고 nextAttemptAt만 갱신
        assertThat(reloaded.getRetryCount()).isEqualTo(3);
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    /**
     * 갓 등록된 PENDING 상태 알림 생성.
     */
    private Notification freshNotification() {
        return Notification.create(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP,
                "ref-" + UUID.randomUUID().toString().substring(0, 8),
                Map.of("studentName", "테스트", "courseName", "재시도")
        );
    }

    /**
     * retryCount회 실패한 FAILED 상태 알림 생성.
     *
     * <p>도메인 메서드만 사용해 자연스러운 상태 전이 시퀀스를 거침.
     * 첫 실패는 PENDING → PROCESSING → FAILED, 추가 실패는 scheduleRetry → PROCESSING → FAILED 반복.
     *
     * @param retryCount 1 이상의 실패 횟수
     */
    private Notification createFailedNotification(int retryCount) {
        if (retryCount < 1) {
            throw new IllegalArgumentException("retryCount must be >= 1");
        }
        Notification n = freshNotification();
        n.startProcessing("worker-setup");
        n.markAsFailed("setup-failure-0");

        for (int i = 1; i < retryCount; i++) {
            n.scheduleRetry(LocalDateTime.now());   // FAILED → PENDING
            n.startProcessing("worker-setup");       // PENDING → PROCESSING
            n.markAsFailed("setup-failure-" + i);    // PROCESSING → FAILED, retryCount++
        }
        return n;
    }
}
