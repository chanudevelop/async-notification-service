package com.chanudevelop.notification.application;

import com.chanudevelop.notification.NotificationApplication;
import com.chanudevelop.notification.TestcontainersConfiguration;
import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.NotificationType;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 조회 API 통합 테스트.
 *
 * <p>핵심 검증:
 * <ul>
 *   <li>알림 상세 조회 (200, 404)</li>
 *   <li>본인 알림 목록 조회 — ADR-011 비즈니스 룰: SENT + IN_APP만 노출</li>
 *   <li>읽음 필터, 페이지네이션</li>
 *   <li>X-User-Id 헤더 검증</li>
 * </ul>
 */
@SpringBootTest(classes = NotificationApplication.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class NotificationQueryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    // ===== 테스트 헬퍼 =====

    private Notification savePendingInApp(String userId, String referenceId) {
        Notification n = Notification.create(
                userId, NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP, referenceId, Map.of()
        );
        return notificationRepository.save(n);
    }

    private Notification saveSentInApp(String userId, String referenceId, boolean read) {
        Notification n = Notification.create(
                userId, NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP, referenceId, Map.of()
        );
        n.startProcessing("test-worker");
        n.markAsSent("수강 신청이 완료되었습니다", "본문 내용");
        if (read) {
            n.markAsRead();
        }
        return notificationRepository.save(n);
    }

    private Notification saveSentEmail(String userId, String referenceId) {
        Notification n = Notification.create(
                userId, NotificationType.PAYMENT_CONFIRMED,
                NotificationChannel.EMAIL, referenceId, Map.of()
        );
        n.startProcessing("test-worker");
        n.markAsSent("결제 완료", "본문 내용");
        return notificationRepository.save(n);
    }

    private Notification saveDeadLetterInApp(String userId, String referenceId) {
        Notification n = Notification.create(
                userId, NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP, referenceId, Map.of()
        );
        n.startProcessing("test-worker");
        n.markAsFailed("test failure");
        n.moveToDeadLetter();
        return notificationRepository.save(n);
    }

    // ===== 알림 상세 조회 =====

    @Test
    @DisplayName("알림 ID로 상세를 조회하면 200과 풍부한 정보를 반환한다")
    void getDetail_success() throws Exception {
        Notification saved = savePendingInApp("user-001", "enrollment-001");

        mockMvc.perform(get("/notifications/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.recipientId").value("user-001"))
                .andExpect(jsonPath("$.type").value("ENROLLMENT_COMPLETED"))
                .andExpect(jsonPath("$.channel").value("IN_APP"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.maxRetry").value(5));
    }

    @Test
    @DisplayName("존재하지 않는 알림 ID 조회 시 404와 NOTIFICATION_NOT_FOUND 에러를 반환한다")
    void getDetail_notFound_returns_404() throws Exception {
        mockMvc.perform(get("/notifications/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ===== 본인 알림 목록 — X-User-Id 검증 =====

    @Test
    @DisplayName("X-User-Id 헤더가 없으면 400과 INVALID_REQUEST 에러를 반환한다")
    void getMyNotifications_missing_header_returns_400() throws Exception {
        mockMvc.perform(get("/notifications/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Missing required header: X-User-Id"));
    }

    @Test
    @DisplayName("받은 알림이 없으면 빈 페이지 응답을 반환한다")
    void getMyNotifications_empty() throws Exception {
        mockMvc.perform(get("/notifications/me")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    // ===== ADR-011 비즈니스 룰 검증 (핵심) =====

    @Test
    @DisplayName("SENT 상태의 IN_APP 알림은 목록에 포함된다")
    void getMyNotifications_sent_inapp_visible() throws Exception {
        saveSentInApp("user-001", "enrollment-001", false);

        mockMvc.perform(get("/notifications/me")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("ENROLLMENT_COMPLETED"))
                .andExpect(jsonPath("$.content[0].channel").value("IN_APP"))
                .andExpect(jsonPath("$.content[0].isRead").value(false));
    }

    @Test
    @DisplayName("PENDING 상태 알림은 목록에 포함되지 않는다 (ADR-011: 받은 알림만)")
    void getMyNotifications_pending_hidden() throws Exception {
        savePendingInApp("user-001", "enrollment-001");

        mockMvc.perform(get("/notifications/me")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("EMAIL 채널 알림은 목록에 포함되지 않는다 (ADR-011: IN_APP만)")
    void getMyNotifications_email_hidden() throws Exception {
        saveSentEmail("user-001", "payment-001");

        mockMvc.perform(get("/notifications/me")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("DEAD_LETTER 상태 알림은 목록에 포함되지 않는다 (ADR-011: SENT만)")
    void getMyNotifications_deadletter_hidden() throws Exception {
        saveDeadLetterInApp("user-001", "enrollment-001");

        mockMvc.perform(get("/notifications/me")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("여러 상태/채널 혼합 시 SENT + IN_APP 알림만 목록에 노출된다 (ADR-011 종합)")
    void getMyNotifications_mixed_only_sent_inapp_visible() throws Exception {
        saveSentInApp("user-001", "enrollment-001", false);   // ✅ 노출
        savePendingInApp("user-001", "enrollment-002");        // ❌ PENDING
        saveSentEmail("user-001", "payment-001");              // ❌ EMAIL
        saveDeadLetterInApp("user-001", "enrollment-003");     // ❌ DEAD_LETTER

        mockMvc.perform(get("/notifications/me")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].channel").value("IN_APP"));
    }

    @Test
    @DisplayName("다른 사용자의 알림은 본인 목록에 포함되지 않는다")
    void getMyNotifications_isolated_by_user() throws Exception {
        saveSentInApp("user-001", "enrollment-001", false);
        saveSentInApp("user-002", "enrollment-002", false);

        mockMvc.perform(get("/notifications/me")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ===== 읽음 필터 =====

    @Test
    @DisplayName("read=true 파라미터는 읽은 알림만 반환한다")
    void getMyNotifications_filter_read_true() throws Exception {
        saveSentInApp("user-001", "ref-1", false);   // 안 읽음
        saveSentInApp("user-001", "ref-2", true);    // 읽음

        mockMvc.perform(get("/notifications/me")
                        .param("read", "true")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].isRead").value(true));
    }

    @Test
    @DisplayName("read=false 파라미터는 안 읽은 알림만 반환한다")
    void getMyNotifications_filter_read_false() throws Exception {
        saveSentInApp("user-001", "ref-1", false);
        saveSentInApp("user-001", "ref-2", true);

        mockMvc.perform(get("/notifications/me")
                        .param("read", "false")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].isRead").value(false));
    }

    // ===== 페이지네이션 =====

    @Test
    @DisplayName("size 파라미터로 페이지 크기를 제어하고 totalPages/hasNext가 정확하다")
    void getMyNotifications_pagination() throws Exception {
        saveSentInApp("user-001", "ref-1", false);
        saveSentInApp("user-001", "ref-2", false);
        saveSentInApp("user-001", "ref-3", false);

        mockMvc.perform(get("/notifications/me")
                        .param("size", "2")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    @DisplayName("page 파라미터로 다음 페이지 조회 시 남은 항목과 hasNext=false를 반환한다")
    void getMyNotifications_pagination_last_page() throws Exception {
        saveSentInApp("user-001", "ref-1", false);
        saveSentInApp("user-001", "ref-2", false);
        saveSentInApp("user-001", "ref-3", false);

        mockMvc.perform(get("/notifications/me")
                        .param("size", "2")
                        .param("page", "1")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(1));
    }
}
