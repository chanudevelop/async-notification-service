package com.chanudevelop.notification.application.dto;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationListItem(
        UUID id,
        NotificationType type,
        NotificationChannel channel,
        String title,
        String body,
        boolean isRead,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {

    public static NotificationListItem of(Notification n) {
        return new NotificationListItem(
                n.getId(),
                n.getType(),
                n.getChannel(),
                n.getTitle(),
                n.getBody(),
                n.getReadAt() != null,
                n.getCreatedAt(),
                n.getReadAt()
        );
    }
}
