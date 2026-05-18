package com.chanudevelop.notification.domain.exception;

import com.chanudevelop.notification.domain.NotificationStatus;

public class IllegalStateTransitionException extends DomainException {

    public IllegalStateTransitionException(NotificationStatus current, NotificationStatus expected, String action) {
        super(
                ErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot " + action + " from status: " + current + " (expected: " + expected + ")"
        );
    }

    public IllegalStateTransitionException(String message) {
        super(ErrorCode.INVALID_STATUS_TRANSITION, message);
    }
}
