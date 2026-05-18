package com.chanudevelop.notification.application.worker;

import com.chanudevelop.notification.application.ReaperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Stuck Recovery 워커 (@Scheduled 기반).
 *
 * <p>{@link NotificationProcessor}(PENDING), {@link RetryProcessor}(FAILED)와 책임 분리:
 * 본 워커는 PROCESSING 상태에서 일정 시간 이상 갇힌 알림만 본다.
 *
 * <p>폴링 주기는 1분(application.yaml에서 외부화). Stuck은 즉시성보다 안정성 중요.
 * 임계값(stuck-threshold-seconds 기본 5분)을 외부 시스템 timeout보다 충분히 길게 두어
 * 살아 있는 워커의 작업을 가로채는 사고 방지.
 *
 * <p>{@code @ConditionalOnProperty}로 비활성화 가능 — 운영에서 따로 끄거나 테스트에서 격리.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class StuckReaperProcessor {

    private final ReaperService reaperService;

    public StuckReaperProcessor(ReaperService reaperService) {
        this.reaperService = reaperService;
        log.info("StuckReaperProcessor initialized");
    }

    @Scheduled(fixedDelayString = "${notification.reaper.polling-interval-ms}")
    public void poll() {
        try {
            List<UUID> stuckIds = reaperService.pollStuck();
            if (stuckIds.isEmpty()) {
                return;
            }
            for (UUID id : stuckIds) {
                reaperService.recoverOne(id);
            }
        } catch (Exception e) {
            log.error("[REAPER-WORKER] poll cycle failed, will retry next interval", e);
        }
    }
}
