package com.chanudevelop.notification.domain;

import lombok.Getter;

@Getter
public enum NotificationType {

    ENROLLMENT_COMPLETED("수강 신청 완료", "enrollment"),
    ENROLLMENT_CANCELLED("수강 취소", "enrollment"),
    PAYMENT_CONFIRMED("결제 확정", "payment"),
    CLASS_STARTS_TOMORROW("강의 시작 D-1", "class");

    private final String displayName;
    private final String category;

    NotificationType(String displayName, String category) {
        this.displayName = displayName;
        this.category = category;
    }

}
