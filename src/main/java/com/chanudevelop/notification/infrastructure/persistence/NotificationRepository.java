package com.chanudevelop.notification.infrastructure.persistence;

import com.chanudevelop.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

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
