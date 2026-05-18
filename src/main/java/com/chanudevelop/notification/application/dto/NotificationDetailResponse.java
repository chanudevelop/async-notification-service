package com.chanudevelop.notification.application.dto;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.NotificationStatus;
import com.chanudevelop.notification.domain.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationDetailResponse(
        UUID id,
        String idempotencyKey,
        String recipientId,
        NotificationType type,
        NotificationChannel channel,
        String referenceId,
        NotificationStatus status,
        String title,
        String body,
        int retryCount,
        int maxRetry,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime sentAt,
        LocalDateTime readAt,
        LocalDateTime updatedAt
) {

    public static NotificationDetailResponse of(Notification n) {
        return new NotificationDetailResponse(
                n.getId(),
                n.getIdempotencyKey(),
                n.getRecipientId(),
                n.getType(),
                n.getChannel(),
                n.getReferenceId(),
                n.getStatus(),
                n.getTitle(),
                n.getBody(),
                n.getRetryCount(),
                n.getMaxRetry(),
                n.getLastError(),
                n.getCreatedAt(),
                n.getSentAt(),
                n.getReadAt(),
                n.getUpdatedAt()
        );
    }
}
