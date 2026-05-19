package com.chanudevelop.notification.infrastructure.persistence;

import com.chanudevelop.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * 발송 워커가 PENDING 알림을 클레임하기 위한 폴링 쿼리.
     *
     * <p>PostgreSQL의 {@code FOR UPDATE SKIP LOCKED}로 다중 워커 인스턴스 환경에서
     * 같은 알림을 중복 처리하지 않도록 보장한다. 다른 워커가 락 잡은 행은 대기 없이 건너뛴다.
     *
     * <p>호출자는 반드시 {@code @Transactional} 안에서 호출해야 한다 (락이 트랜잭션에 묶임).
     * 반환된 엔티티에 대해 {@code notification.startProcessing(workerId)}를 호출하면
     * dirty checking으로 트랜잭션 종료 시 UPDATE가 자동 실행된다.
     *
     * <p>Partial Index {@code idx_pending_polling} 활용 (V1 마이그레이션 참조).
     *
     * @param batchSize 한 번에 가져올 최대 알림 수
     * @return 클레임 가능한 PENDING 알림 목록 (next_attempt_at 오름차순)
     */
    @Query(value = """
            SELECT * FROM notifications
             WHERE status = 'PENDING' AND next_attempt_at <= NOW()
             ORDER BY next_attempt_at
             FOR UPDATE SKIP LOCKED
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Notification> findPendingForUpdate(@Param("batchSize") int batchSize);

    /**
     * 재시도 워커가 FAILED 알림을 클레임하기 위한 폴링 쿼리.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}로 다중 인스턴스 환경에서도 안전.
     * retryCount vs maxRetry 분기는 자바 코드의 {@code canBeRetried()} 도메인 메서드에서 처리한다
     * (Rich Domain Model 일관성 — ADR-013 Sub-Decision 4).
     *
     * <p>호출자는 반드시 {@code @Transactional} 안에서 호출해야 한다.
     *
     * @param batchSize 한 번에 가져올 최대 알림 수
     * @return FAILED 상태 알림 목록 (failed_at 오름차순)
     */
    @Query(value = """
            SELECT * FROM notifications
             WHERE status = 'FAILED'
             ORDER BY failed_at
             FOR UPDATE SKIP LOCKED
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Notification> findFailedReadyForRetry(@Param("batchSize") int batchSize);

    /**
     * Stuck Reaper가 PROCESSING 갇힌 알림을 복구하기 위한 폴링 쿼리.
     *
     * <p>{@code claimed_at < threshold} 조건으로 일정 시간 이상 갇힌 알림만 잡는다.
     * threshold는 호출자가 {@code LocalDateTime.now().minusSeconds(stuckThresholdSeconds)}로 계산해 전달.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}로 다중 Reaper 인스턴스 환경에서도 안전.
     *
     * <p>Partial Index {@code idx_stuck_reaper} 활용 (V1 마이그레이션 참조).
     *
     * @param threshold 이 시각 이전에 claim된 알림만 stuck으로 간주
     * @param batchSize 한 번에 가져올 최대 알림 수
     * @return Stuck 상태 알림 목록 (claimed_at 오름차순)
     */
    @Query(value = """
            SELECT * FROM notifications
             WHERE status = 'PROCESSING'
               AND claimed_at < :threshold
             ORDER BY claimed_at
             FOR UPDATE SKIP LOCKED
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Notification> findStuckForRecovery(
            @Param("threshold") LocalDateTime threshold,
            @Param("batchSize") int batchSize
    );

    /**
     * 읽음 처리 조건부 UPDATE — 여러 기기 동시 호출 안전.
     *
     * <p>{@code WHERE read_at IS NULL} 조건이 핵심. DB가 첫 호출만 1행 갱신하고
     * 이후 동시 호출은 0행 갱신으로 자연스럽게 차단된다. 알림 등록 멱등성의
     * DB UNIQUE 제약과 같은 "DB가 단일 진실 원천" 패턴.
     *
     * <p>자바 메모리에서 {@code if (readAt == null)} 검사하는 방식은 동시 환경에서
     * 두 스레드가 모두 통과해 readAt이 last-writer-wins로 덮이지만, 본 메서드는
     * DB 차원에서 정확히 첫 호출만 통과시킴.
     *
     * @param id 알림 ID
     * @param now 읽음 처리 시각
     * @return 갱신된 행 수 (1 = 이번 호출이 첫 읽음, 0 = 이미 읽었거나 알림 없음)
     */
    @Modifying
    @Query("""
            UPDATE Notification n
               SET n.readAt = :now
             WHERE n.id = :id
               AND n.readAt IS NULL
            """)
    int markAsReadIfUnread(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * 사용자가 받은 IN_APP 알림 목록 조회 (비즈니스 룰: SENT + IN_APP, ADR-011).
     *
     * <p>읽음 필터:
     * <ul>
     *   <li>{@code read = null}: 전체</li>
     *   <li>{@code read = true}: 읽은 것만 (readAt IS NOT NULL)</li>
     *   <li>{@code read = false}: 안 읽은 것만 (readAt IS NULL)</li>
     * </ul>
     */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientId = :recipientId
              AND n.status = com.chanudevelop.notification.domain.NotificationStatus.SENT
              AND n.channel = com.chanudevelop.notification.domain.NotificationChannel.IN_APP
              AND (:read IS NULL
                   OR (:read = TRUE AND n.readAt IS NOT NULL)
                   OR (:read = FALSE AND n.readAt IS NULL))
            ORDER BY n.createdAt DESC
            """)
    Page<Notification> findReceivedInAppByRecipient(
            @Param("recipientId") String recipientId,
            @Param("read") Boolean read,
            Pageable pageable
    );
}
