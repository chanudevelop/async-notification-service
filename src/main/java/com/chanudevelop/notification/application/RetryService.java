package com.chanudevelop.notification.application;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.retry.BackoffCalculator;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * FAILED 알림의 재시도 또는 DEAD_LETTER 전이를 책임지는 application 서비스.
 *
 * <p>두 트랜잭션 메서드로 분리 (Phase 6 ProcessingService와 동일 패턴):
 * <ul>
 *   <li>{@link #pollFailed()}: FAILED 알림을 batchSize만큼 잡아 ID 반환 (짧은 트랜잭션)</li>
 *   <li>{@link #retryOne(UUID)}: 알림 1건당 별도 트랜잭션으로 재시도 또는 DEAD_LETTER</li>
 * </ul>
 *
 * <p>묶음 호출은 외부 워커({@code RetryProcessor})가 수행한다. Spring AOP proxy
 * self-invocation 함정 회피 (TS-001 참조).
 *
 * <p>분기 로직 — {@code canBeRetried()} 도메인 메서드로:
 * <ul>
 *   <li>재시도 가능: {@link Notification#scheduleRetry(LocalDateTime)} → FAILED → PENDING (백오프 시각)</li>
 *   <li>한계 초과: {@link Notification#moveToDeadLetter()} → FAILED → DEAD_LETTER</li>
 * </ul>
 */
@Slf4j
@Service
public class RetryService {

    private final NotificationRepository notificationRepository;
    private final BackoffCalculator backoffCalculator;
    private final int batchSize;

    public RetryService(
            NotificationRepository notificationRepository,
            BackoffCalculator backoffCalculator,
            @org.springframework.beans.factory.annotation.Value("${notification.retry.batch-size}") int batchSize
    ) {
        this.notificationRepository = notificationRepository;
        this.backoffCalculator = backoffCalculator;
        this.batchSize = batchSize;
        log.info("RetryService initialized: batchSize={}", batchSize);
    }

    /**
     * FAILED 알림을 batchSize만큼 잡아 ID 반환.
     * 트랜잭션 짧게 유지 — 실제 분기 처리는 retryOne에서.
     */
    @Transactional
    public List<UUID> pollFailed() {
        List<Notification> candidates = notificationRepository.findFailedReadyForRetry(batchSize);
        List<UUID> ids = candidates.stream().map(Notification::getId).toList();
        if (!ids.isEmpty()) {
            log.info("[RETRY-POLL] picked {} failed notifications: {}", ids.size(), ids);
        }
        return ids;
    }

    /**
     * 한 알림에 대해 재시도 또는 DEAD_LETTER 전이.
     *
     * <p>{@code canBeRetried()} 도메인 메서드로 분기:
     * <ul>
     *   <li>true → 백오프 계산 → {@code scheduleRetry(now + delay)} → PENDING</li>
     *   <li>false → {@code moveToDeadLetter()} → DEAD_LETTER</li>
     * </ul>
     */
    @Transactional
    public void retryOne(UUID notificationId) {
        Notification n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("Notification not found: " + notificationId));

        if (n.canBeRetried()) {
            Duration delay = backoffCalculator.next(n.getRetryCount());
            LocalDateTime nextAttempt = LocalDateTime.now().plus(delay);
            n.scheduleRetry(nextAttempt);
            log.info("[RETRY] id={} → PENDING (retryCount={}, nextAttemptAt={}, delay={}s)",
                    notificationId, n.getRetryCount(), nextAttempt, delay.toSeconds());
        } else {
            n.moveToDeadLetter();
            log.warn("[RETRY] id={} → DEAD_LETTER (retryCount={}/{})",
                    notificationId, n.getRetryCount(), 5);
        }
    }
}
