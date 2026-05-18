package com.chanudevelop.notification.domain.exception;

import java.util.UUID;

public class NotificationNotFoundException extends DomainException {

    public NotificationNotFoundException(UUID id) {
        super(ErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found: " + id);
    }
}
