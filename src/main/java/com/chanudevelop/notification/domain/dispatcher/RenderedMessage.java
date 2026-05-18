package com.chanudevelop.notification.domain.dispatcher;

/**
 * 템플릿 + payload를 합쳐 렌더링한 최종 메시지.
 *
 * <p>워커가 발송 시점에 만들어 Dispatcher에 전달한다.
 * Dispatcher는 이 객체의 title/body로 외부 시스템(SMTP 등)을 호출하고,
 * Worker는 발송 성공 후 {@code notification.markAsSent(title, body)}로 DB에 저장한다.
 */
public record RenderedMessage(String title, String body) {
}
