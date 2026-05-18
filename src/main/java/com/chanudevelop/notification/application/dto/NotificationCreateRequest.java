package com.chanudevelop.notification.application.dto;

import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record NotificationCreateRequest(

        // 요청 값: 수신자, 알림 타입, 채널(이메일/인앱), 이벤트id, payload(메시지 만들 때의 변수 데이터)
        @NotBlank
        @Size(max = 64)
        String recipientId,

        @NotNull
        NotificationType type,

        @NotNull
        NotificationChannel channel,

        @NotBlank
        @Size(max = 64)
        String referenceId,

        Map<String, Object> payload
) {
}
