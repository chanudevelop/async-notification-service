package com.chanudevelop.notification.infrastructure.persistence;

import com.chanudevelop.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
