package com.chanudevelop.notification.domain.retry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential Backoff + Jitter 계산기 (도메인 서비스).
 *
 * <p>공식: {@code delay = min(base * multiplier^(retryCount-1), max) + random(0, jitter)}
 *
 * <p>외부 의존이 없는 순수 함수라 도메인 안쪽에 배치.
 * 파라미터는 {@code notification.retry.*} 설정값으로 외부화 — 운영 시 튜닝 가능.
 *
 * <p>retryCount는 {@code Notification.markAsFailed()}에서 1부터 증가하므로
 * 본 계산기도 1을 첫 시도(첫 재시도) 기준으로 가정.
 *
 * @see com.chanudevelop.notification.application.RetryService
 */
@Component
public class BackoffCalculator {

    private final long baseSeconds;
    private final double multiplier;
    private final long maxSeconds;
    private final long jitterSeconds;

    public BackoffCalculator(
            @Value("${notification.retry.base-delay-seconds}") long baseSeconds,
            @Value("${notification.retry.multiplier}") double multiplier,
            @Value("${notification.retry.max-delay-seconds}") long maxSeconds,
            @Value("${notification.retry.jitter-seconds}") long jitterSeconds
    ) {
        this.baseSeconds = baseSeconds;
        this.multiplier = multiplier;
        this.maxSeconds = maxSeconds;
        this.jitterSeconds = jitterSeconds;
    }

    /**
     * retryCount 회 실패한 알림의 다음 재시도까지 대기 시간 계산.
     *
     * @param retryCount 현재까지 실패 횟수 (1 이상)
     * @return 다음 재시도까지 Duration
     */
    public Duration next(int retryCount) {
        if (retryCount < 1) {
            throw new IllegalArgumentException("retryCount must be >= 1, got " + retryCount);
        }
        long expSeconds = (long) (baseSeconds * Math.pow(multiplier, retryCount - 1));
        long capped = Math.min(expSeconds, maxSeconds);
        long jitter = jitterSeconds > 0
                ? ThreadLocalRandom.current().nextLong(0, jitterSeconds + 1)
                : 0;
        return Duration.ofSeconds(capped + jitter);
    }
}
