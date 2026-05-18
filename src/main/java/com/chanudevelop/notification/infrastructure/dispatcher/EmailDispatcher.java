package com.chanudevelop.notification.infrastructure.dispatcher;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.dispatcher.NotificationDispatcher;
import com.chanudevelop.notification.domain.dispatcher.RenderedMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * EMAIL 채널 Dispatcher (Mock 구현).
 *
 * <p>본 과제 범위에선 실제 SMTP 호출을 하지 않고 콘솔 로그로 발송을 흉내낸다.
 * 운영 환경 전환 시 이 클래스만 실제 SMTP/SES/SendGrid 호출 코드로 교체하면 된다.
 * 인터페이스(`NotificationDispatcher`)는 그대로라 호출자(Worker) 코드 변경 X.
 */
@Slf4j
@Component
public class EmailDispatcher implements NotificationDispatcher {

    @Override
    public NotificationChannel supportedChannel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void dispatch(Notification notification, RenderedMessage rendered) {
        // 운영 시: SMTP 클라이언트로 메일 발송
        //   - to: notification.getRecipientId()의 이메일 주소 (별도 사용자 서비스에서 조회 필요)
        //   - subject: rendered.title()
        //   - body: rendered.body()
        //
        // Mock: 콘솔 로그로 발송 흉내
        log.info("[EMAIL] sent to {} (notification-id={}, type={}, title={}, body={})",
                notification.getRecipientId(),
                notification.getId(),
                notification.getType(),
                rendered.title(),
                rendered.body());
    }
}
