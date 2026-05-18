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
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 워커 흐름 통합 테스트.
 *
 * <p>워커 본체(@Scheduled)는 application-test.yaml에서 폴링 간격을 1시간으로 설정하여 사실상 비활성화.
 * 본 테스트는 NotificationProcessingService의 두 메서드(claimPending, processOne)를 직접 호출해
 * 워커가 1회 폴링 사이클을 도는 동작을 시뮬레이션한다.
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>PENDING → claimPending 호출 → PROCESSING 전이 + workerId 기록</li>
 *   <li>PROCESSING → processOne 호출 → 템플릿 렌더 + Dispatcher 호출 + SENT 전이 + title/body 저장</li>
 *   <li>변수 누락 payload → processOne 호출 → FAILED 전이 + lastError 기록</li>
 *   <li>PENDING 없을 때 → claimPending 호출 → 빈 리스트 반환</li>
 *   <li>다중 호출 시 같은 알림이 두 번 클레임되지 않음 (PENDING만 대상)</li>
 * </ul>
 */
@SpringBootTest(classes = NotificationApplication.class)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class NotificationWorkerIntegrationTest {

    @Autowired
    private NotificationProcessingService processingService;

    @Autowired
    private NotificationRepository notificationRepository;

    private static final String TEST_WORKER_ID = "worker-test-12345678";

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("claimPending 호출 시 PENDING 알림이 PROCESSING으로 전이되고 workerId가 기록된다")
    void claimPending_transitions_to_processing() {
        Notification pending = Notification.create(
                "user-001",
                NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP,
                "enrollment-001",
                Map.of("studentName", "김철수", "courseName", "Spring 입문")
        );
        notificationRepository.saveAndFlush(pending);

        List<UUID> claimed = processingService.claimPending(TEST_WORKER_ID);

        assertThat(claimed).hasSize(1);

        Notification reloaded = notificationRepository.findById(claimed.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(reloaded.getWorkerId()).isEqualTo(TEST_WORKER_ID);
        assertThat(reloaded.getClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("processOne 호출 시 템플릿 렌더 + Dispatcher 호출 후 SENT 전이 + title/body 저장")
    void processOne_renders_and_marks_sent() {
        Notification pending = Notification.create(
                "user-002",
                NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP,
                "enrollment-002",
                Map.of("studentName", "이영희", "courseName", "JPA 마스터")
        );
        notificationRepository.saveAndFlush(pending);

        List<UUID> claimed = processingService.claimPending(TEST_WORKER_ID);
        assertThat(claimed).hasSize(1);

        processingService.processOne(claimed.get(0));

        Notification reloaded = notificationRepository.findById(claimed.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getTitle()).isEqualTo("수강 신청이 완료되었습니다");
        assertThat(reloaded.getBody()).isEqualTo("이영희님, JPA 마스터 강의 신청이 완료되었습니다.");
        assertThat(reloaded.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("payload에 템플릿 변수가 누락되면 FAILED 전이 + lastError 기록")
    void processOne_missing_variable_marks_failed() {
        // 템플릿: "{{studentName}}님, {{courseName}} 강의 신청이 완료되었습니다."
        // payload에 courseName 누락
        Notification pending = Notification.create(
                "user-003",
                NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP,
                "enrollment-003",
                Map.of("studentName", "박지민")
        );
        notificationRepository.saveAndFlush(pending);

        List<UUID> claimed = processingService.claimPending(TEST_WORKER_ID);
        assertThat(claimed).hasSize(1);

        processingService.processOne(claimed.get(0));

        Notification reloaded = notificationRepository.findById(claimed.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(reloaded.getLastError()).contains("Unresolved template placeholder");
        assertThat(reloaded.getFailedAt()).isNotNull();
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PENDING 알림이 없으면 claimPending은 빈 리스트를 반환한다")
    void claimPending_empty_when_no_pending() {
        List<UUID> claimed = processingService.claimPending(TEST_WORKER_ID);
        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("두 번 호출해도 같은 알림이 두 번 클레임되지 않는다 (PROCESSING은 폴링 대상 X)")
    void claimPending_does_not_pick_already_processing() {
        Notification pending = Notification.create(
                "user-004",
                NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP,
                "enrollment-004",
                Map.of("studentName", "최지원", "courseName", "Kafka")
        );
        notificationRepository.saveAndFlush(pending);

        List<UUID> firstClaim = processingService.claimPending(TEST_WORKER_ID);
        List<UUID> secondClaim = processingService.claimPending(TEST_WORKER_ID);

        assertThat(firstClaim).hasSize(1);
        assertThat(secondClaim).isEmpty();
    }

    @Test
    @DisplayName("EMAIL 채널 알림도 channel=NULL 공통 템플릿으로 렌더링되어 SENT 처리된다 (fallback 검증)")
    void processOne_emailChannel_falls_back_to_common_template() {
        Notification pending = Notification.create(
                "user-005",
                NotificationType.PAYMENT_CONFIRMED,
                NotificationChannel.EMAIL,
                "payment-001",
                Map.of("amount", "50000")
        );
        notificationRepository.saveAndFlush(pending);

        List<UUID> claimed = processingService.claimPending(TEST_WORKER_ID);
        processingService.processOne(claimed.get(0));

        Notification reloaded = notificationRepository.findById(claimed.get(0)).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getTitle()).isEqualTo("결제가 완료되었습니다");
        assertThat(reloaded.getBody()).isEqualTo("50000원 결제가 완료되었습니다.");
    }
}
