package com.chanudevelop.notification.infrastructure.dispatcher;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.dispatcher.NotificationDispatcher;
import com.chanudevelop.notification.domain.dispatcher.RenderedMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * IN_APP 채널 Dispatcher.
 *
 * <p>본질적으로 외부 발송이 없는 채널이다. 사용자는 자신의 알림 페이지 조회 API
 * (`GET /notifications/me`)를 통해 SENT 상태의 IN_APP 알림을 본다.
 *
 * <p>워커가 markAsSent로 title/body를 DB에 저장하면 사용자가 즉시 볼 수 있다.
 * Dispatcher 자체는 발송 흔적을 위한 로그만 남긴다.
 *
 * <p>향후 푸시 알림(FCM/APNs)을 통합한다면 이 클래스에서 푸시 전송 로직을 추가할 수 있다.
 */
@Slf4j
@Component
public class InAppDispatcher implements NotificationDispatcher {

    @Override
    public NotificationChannel supportedChannel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public void dispatch(Notification notification, RenderedMessage rendered) {
        // IN_APP은 외부 발송 없음. DB의 SENT 상태가 곧 사용자에게 노출됨.
        log.info("[IN_APP] delivered to {} (notification-id={}, type={}, title={})",
                notification.getRecipientId(),
                notification.getId(),
                notification.getType(),
                rendered.title());
    }
}
