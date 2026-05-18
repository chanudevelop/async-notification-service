package com.chanudevelop.notification.application.worker;

import com.chanudevelop.notification.application.RetryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * FAILED 알림 재시도 / DEAD_LETTER 전이 워커 (@Scheduled 기반).
 *
 * <p>{@link com.chanudevelop.notification.application.worker.NotificationProcessor}와
 * 책임 분리:
 * <ul>
 *   <li>NotificationProcessor: PENDING → PROCESSING → SENT/FAILED</li>
 *   <li>RetryProcessor (본 클래스): FAILED → PENDING(재시도) 또는 DEAD_LETTER(포기)</li>
 * </ul>
 *
 * <p>폴링 주기는 발송 워커(1초)보다 느슨한 5초 (application.yaml에서 외부화).
 * 재시도는 즉시성보다 안정성 중요.
 *
 * <p>{@code @ConditionalOnProperty}로 비활성화 가능 — 운영에서 외부 시스템 장애 시
 * 재시도만 잠시 끄거나, 통합 테스트에서 자동 트리거 차단 (TS-006 패턴).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.retry.enabled", havingValue = "true", matchIfMissing = true)
public class RetryProcessor {

    private final RetryService retryService;

    public RetryProcessor(RetryService retryService) {
        this.retryService = retryService;
        log.info("RetryProcessor initialized");
    }

    @Scheduled(fixedDelayString = "${notification.retry.polling-interval-ms}")
    public void poll() {
        try {
            List<UUID> claimedIds = retryService.pollFailed();
            if (claimedIds.isEmpty()) {
                return;
            }
            for (UUID id : claimedIds) {
                retryService.retryOne(id);
            }
        } catch (Exception e) {
            log.error("[RETRY-WORKER] poll cycle failed, will retry next interval", e);
        }
    }
}
