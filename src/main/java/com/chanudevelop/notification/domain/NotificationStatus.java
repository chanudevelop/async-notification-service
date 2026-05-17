package com.chanudevelop.notification.domain;

public enum NotificationStatus {
    PENDING, PROCESSING, SENT, FAILED, DEAD_LETTER;

    public boolean canBeClaimed() {
        return this == PENDING;
    }

    public boolean canRetry() {
        return this == FAILED;
    }

    public boolean isTerminal() {
        return this == SENT;
    }
}
