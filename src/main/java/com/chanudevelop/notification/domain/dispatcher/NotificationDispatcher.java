package com.chanudevelop.notification.domain.dispatcher;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;

/**
 * 알림 발송을 추상화한 도메인 계약 (Port).
 *
 * <p>구현체는 각 채널별로 분리한다 (예: EmailDispatcher, InAppDispatcher).
 * 한 구현체는 한 채널만 담당하며, {@link #supportedChannel()}로 자기 담당 채널을 명시한다.
 *
 * <p>워커는 시작 시 모든 구현체를 {@code List<NotificationDispatcher>}로 주입받아
 * {@code Map<NotificationChannel, NotificationDispatcher>}로 캐싱한다.
 * 발송 시점에는 알림의 channel로 Map을 조회해 적합한 구현체를 즉시 찾는다.
 *
 * <p>새 채널 추가 시 새 구현체만 만들면 자동으로 통합된다 (OCP).
 */
public interface NotificationDispatcher {

    /**
     * 이 Dispatcher가 담당하는 채널.
     */
    NotificationChannel supportedChannel();

    /**
     * 알림을 외부 시스템으로 발송한다.
     *
     * @param notification 발송할 알림 (이 시점에 title/body는 아직 엔티티에 안 채워져 있음)
     * @param rendered     템플릿 + payload로 렌더링한 최종 메시지 (title, body)
     * @throws RuntimeException 발송 실패 시. 워커는 이를 잡아 FAILED 처리 및 재시도 스케줄링.
     */
    void dispatch(Notification notification, RenderedMessage rendered);
}
