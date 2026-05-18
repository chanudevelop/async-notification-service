package com.chanudevelop.notification.application;

import com.chanudevelop.notification.NotificationApplication;
import com.chanudevelop.notification.TestcontainersConfiguration;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = NotificationApplication.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("정상 등록 요청은 202 Accepted를 반환하고 알림이 PENDING 상태로 저장된다")
    void create_success() throws Exception {
        String requestBody = """
                {
                  "recipientId": "user-001",
                  "type": "ENROLLMENT_COMPLETED",
                  "channel": "EMAIL",
                  "referenceId": "enrollment-123",
                  "payload": {
                    "studentName": "김철수",
                    "courseName": "Spring Boot 입문"
                  }
                }
                """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.isDuplicate").value(false))
                .andExpect(jsonPath("$.idempotencyKey")
                        .value("user-001:ENROLLMENT_COMPLETED:enrollment-123:EMAIL"));

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일한 요청을 두 번 보내면 첫 요청의 id를 반환하고 isDuplicate=true가 된다")
    void create_duplicate_returns_existing() throws Exception {
        String requestBody = """
                {
                  "recipientId": "user-001",
                  "type": "ENROLLMENT_COMPLETED",
                  "channel": "EMAIL",
                  "referenceId": "enrollment-123",
                  "payload": { "studentName": "김철수" }
                }
                """;

        // 첫 요청
        MvcResult firstResult = mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.isDuplicate").value(false))
                .andReturn();

        String firstId = objectMapper
                .readTree(firstResult.getResponse().getContentAsString())
                .get("id").asText();

        // 같은 요청 다시 — 같은 id + isDuplicate=true
        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.isDuplicate").value(true));

        // DB엔 여전히 1건만
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사용자·타입이라도 reference_id가 다르면 별개의 알림으로 등록된다")
    void create_different_referenceId_creates_new() throws Exception {
        String first = """
                {
                  "recipientId": "user-001",
                  "type": "ENROLLMENT_COMPLETED",
                  "channel": "EMAIL",
                  "referenceId": "enrollment-123"
                }
                """;
        String second = """
                {
                  "recipientId": "user-001",
                  "type": "ENROLLMENT_COMPLETED",
                  "channel": "EMAIL",
                  "referenceId": "enrollment-456"
                }
                """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.isDuplicate").value(false));

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.isDuplicate").value(false));

        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 이벤트라도 채널이 다르면 별개의 알림으로 등록된다 (EMAIL + IN_APP 동시 발송 시나리오)")
    void create_different_channel_creates_new() throws Exception {
        String email = """
                {
                  "recipientId": "user-001",
                  "type": "ENROLLMENT_COMPLETED",
                  "channel": "EMAIL",
                  "referenceId": "enrollment-123"
                }
                """;
        String inApp = """
                {
                  "recipientId": "user-001",
                  "type": "ENROLLMENT_COMPLETED",
                  "channel": "IN_APP",
                  "referenceId": "enrollment-123"
                }
                """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(email))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.isDuplicate").value(false));

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inApp))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.isDuplicate").value(false));

        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("필수 필드 recipientId가 누락되면 400 Bad Request를 반환한다")
    void create_missing_recipientId_returns_400() throws Exception {
        String requestBody = """
                {
                  "type": "ENROLLMENT_COMPLETED",
                  "channel": "EMAIL",
                  "referenceId": "enrollment-123"
                }
                """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("필수 필드가 빈 문자열이면 400 Bad Request를 반환한다 (@NotBlank)")
    void create_blank_recipientId_returns_400() throws Exception {
        String requestBody = """
                {
                  "recipientId": "",
                  "type": "ENROLLMENT_COMPLETED",
                  "channel": "EMAIL",
                  "referenceId": "enrollment-123"
                }
                """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        assertThat(notificationRepository.count()).isZero();
    }
}
