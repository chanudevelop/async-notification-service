package com.chanudevelop.notification.domain.exception;

import java.util.UUID;

/**
 * 다른 사용자의 알림에 대한 접근/수정 시도 시 발생.
 *
 * <p>현재는 읽음 처리(markAsRead)에서 본인 알림만 처리 가능하도록 막을 때 사용.
 * 운영 환경에선 SecurityContext + Spring Security로 일관 처리하는 게 표준이지만,
 * 본 과제는 X-User-Id 헤더 간략 인증이라 도메인 예외 단에서 한 번 더 검증.
 */
public class NotificationAccessDeniedException extends DomainException {

    public NotificationAccessDeniedException(UUID notificationId, String requesterUserId) {
        super(
                ErrorCode.NOTIFICATION_ACCESS_DENIED,
                "User " + requesterUserId + " cannot access notification " + notificationId
        );
    }
}
