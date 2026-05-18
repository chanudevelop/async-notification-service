package com.chanudevelop.notification.domain.template.exception;

import com.chanudevelop.notification.domain.exception.DomainException;
import com.chanudevelop.notification.domain.exception.ErrorCode;

/**
 * 템플릿 렌더링 실패 시 던지는 예외.
 *
 * <p>주로 payload에 템플릿 변수가 누락된 경우 발생.
 * 예: 템플릿이 {@code "{{userName}}님, 환영합니다"}인데 payload에 {@code userName} 키가 없는 경우.
 *
 * <p>워커가 이 예외를 catch해 {@code notification.markAsFailed(...)}로 FAILED 처리한다.
 * Fail Fast 원칙: 잘못된 데이터를 조용히 발송하느니 명시적으로 실패 처리.
 */
public class TemplateRenderException extends DomainException {

    public TemplateRenderException(String message) {
        super(ErrorCode.TEMPLATE_RENDER_ERROR, message);
    }
}
