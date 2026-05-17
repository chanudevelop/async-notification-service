package com.chanudevelop.notification.domain;

import lombok.Getter;

@Getter
public enum NotificationChannel {

    EMAIL("이메일"),
    IN_APP("앱 내 알림");

    private final String displayName;

    NotificationChannel(String displayName) {
        this.displayName = displayName;
    }
}
