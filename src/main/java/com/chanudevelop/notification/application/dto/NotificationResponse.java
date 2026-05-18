package com.chanudevelop.notification.application.dto;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String idempotencyKey,
        NotificationStatus status,
        LocalDateTime createdAt,
        boolean isDuplicate
) {

    public static NotificationResponse of(Notification notification, boolean isDuplicate) {
        return new NotificationResponse(
                notification.getId(),
                notification.getIdempotencyKey(),
                notification.getStatus(),
                notification.getCreatedAt(),
                isDuplicate
        );
    }
}
