package com.chanudevelop.notification.application;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationStatus;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Stuck Reaper의 트랜잭션 책임자.
 *
 * <p>워커 프로세스가 PENDING을 PROCESSING으로 클레임한 직후 죽는 경우 그 알림은
 * 영원히 PROCESSING에 갇힌다. 본 서비스가 일정 시간 이상 갇힌 알림을 발견해
 * {@link Notification#recoverFromStuck()}로 PENDING으로 되돌린다.
 *
 * <p>두 트랜잭션 메서드로 분리 (Phase 6 ProcessingService, Phase 7 RetryService와 동일 패턴):
 * <ul>
 *   <li>{@link #pollStuck()}: 임계값 초과 PROCESSING 알림 ID 목록 (짧은 트랜잭션)</li>
 *   <li>{@link #recoverOne(UUID)}: 알림 1건당 별도 트랜잭션으로 recoverFromStuck</li>
 * </ul>
 *
 * <p>recoverFromStuck은 retry_count를 변경하지 않는다.
 * Stuck은 외부 시스템 장애가 아닌 인프라 장애이기에 사용자 알림 도달을 인프라 사고로 지연시키지 않는 정책.
 */
@Slf4j
@Service
public class ReaperService {

    private final NotificationRepository notificationRepository;
    private final int batchSize;
    private final long stuckThresholdSeconds;

    public ReaperService(
            NotificationRepository notificationRepository,
            @Value("${notification.reaper.batch-size}") int batchSize,
            @Value("${notification.reaper.stuck-threshold-seconds}") long stuckThresholdSeconds
    ) {
        this.notificationRepository = notificationRepository;
        this.batchSize = batchSize;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
        log.info("ReaperService initialized: batchSize={}, stuckThresholdSeconds={}",
                batchSize, stuckThresholdSeconds);
    }

    /**
     * 임계값 이상 PROCESSING에 갇힌 알림 ID 목록 반환.
     */
    @Transactional
    public List<UUID> pollStuck() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(stuckThresholdSeconds);
        List<Notification> candidates = notificationRepository.findStuckForRecovery(threshold, batchSize);
        List<UUID> ids = candidates.stream().map(Notification::getId).toList();
        if (!ids.isEmpty()) {
            log.warn("[REAPER-POLL] found {} stuck notifications (threshold={}): {}",
                    ids.size(), threshold, ids);
        }
        return ids;
    }

    /**
     * 한 알림에 대해 stuck 상태 복구 (PROCESSING → PENDING).
     *
     * <p>pollStuck과 recoverOne 사이의 시간 차로 정상 워커가 markAsSent/markAsFailed를
     * 완료할 수 있어, recoverOne에서 status 재검사 후 PROCESSING인 경우만 복구.
     * 도메인 메서드 {@code recoverFromStuck}이 ensureStatus 가드를 갖지만
     * 명시적 검사가 race condition 방어에 더 안전.
     */
    @Transactional
    public void recoverOne(UUID notificationId) {
        Notification n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("Notification not found: " + notificationId));

        if (n.getStatus() == NotificationStatus.PROCESSING) {
            n.recoverFromStuck();
            log.warn("[REAPER] id={} → PENDING (recovered from stuck, retryCount unchanged={})",
                    notificationId, n.getRetryCount());
        } else {
            log.info("[REAPER] id={} skip — already transitioned to {} (race condition handled)",
                    notificationId, n.getStatus());
        }
    }
}
