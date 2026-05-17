package com.chanudevelop.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_recipient_created",
                        columnList = "recipient_id, created_at DESC")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@EqualsAndHashCode(of = "id", callSuper = false)
public class Notification extends BaseEntity {

    private static final int DEFAULT_MAX_RETRY = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "recipient_id", nullable = false, length = 64)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "reference_id", length = 64)
    private String referenceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private NotificationStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    // ===== 정적 팩토리 메서드 =====

    public static Notification create(
            String recipientId,
            NotificationType type,
            NotificationChannel channel,
            String referenceId,
            Map<String, Object> payload
    ) {
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(referenceId, "referenceId must not be null");

        String idempotencyKey = buildIdempotencyKey(recipientId, type, channel, referenceId);

        return Notification.builder()
                .idempotencyKey(idempotencyKey)
                .recipientId(recipientId)
                .type(type)
                .channel(channel)
                .referenceId(referenceId)
                .payload(payload)
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .maxRetry(DEFAULT_MAX_RETRY)
                .nextAttemptAt(LocalDateTime.now())
                .build();
    }

    private static String buildIdempotencyKey(
            String recipientId,
            NotificationType type,
            NotificationChannel channel,
            String referenceId
    ) {
        return String.join(":", recipientId, type.name(), referenceId, channel.name());
    }

    // ===== 도메인 메서드 (상태 전이) =====

    public void startProcessing(String workerId) {
        ensureStatus(NotificationStatus.PENDING, "startProcessing");
        Objects.requireNonNull(workerId, "workerId must not be null");
        this.status = NotificationStatus.PROCESSING;
        this.workerId = workerId;
        this.claimedAt = LocalDateTime.now();
    }

    public void markAsSent(String renderedTitle, String renderedBody) {
        ensureStatus(NotificationStatus.PROCESSING, "markAsSent");
        this.status = NotificationStatus.SENT;
        this.title = renderedTitle;
        this.body = renderedBody;
        this.sentAt = LocalDateTime.now();
    }

    public void markAsFailed(String reason) {
        ensureStatus(NotificationStatus.PROCESSING, "markAsFailed");
        this.status = NotificationStatus.FAILED;
        this.lastError = reason;
        this.failedAt = LocalDateTime.now();
        this.retryCount++;
    }

    public void scheduleRetry(LocalDateTime nextAttemptAt) {
        ensureStatus(NotificationStatus.FAILED, "scheduleRetry");
        if (!canBeRetried()) {
            throw new IllegalStateException(
                    "Cannot schedule retry: max retry exceeded (" + retryCount + "/" + maxRetry + ")");
        }
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        this.status = NotificationStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.workerId = null;
        this.claimedAt = null;
    }

    public void moveToDeadLetter() {
        ensureStatus(NotificationStatus.FAILED, "moveToDeadLetter");
        this.status = NotificationStatus.DEAD_LETTER;
    }

    public void recoverFromStuck() {
        ensureStatus(NotificationStatus.PROCESSING, "recoverFromStuck");
        this.status = NotificationStatus.PENDING;
        this.workerId = null;
        this.claimedAt = null;
    }

    public void manualRetry() {
        ensureStatus(NotificationStatus.DEAD_LETTER, "manualRetry");
        this.status = NotificationStatus.PENDING;
        this.retryCount = 0;
        this.nextAttemptAt = LocalDateTime.now();
        this.workerId = null;
        this.claimedAt = null;
        this.lastError = null;
        this.failedAt = null;
    }

    public void markAsRead() {
        if (this.readAt == null) {
            this.readAt = LocalDateTime.now();
        }
    }

    public boolean canBeRetried() {
        return this.status == NotificationStatus.FAILED && this.retryCount < this.maxRetry;
    }

    // ===== 내부 헬퍼 =====

    private void ensureStatus(NotificationStatus expected, String action) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Cannot " + action + " from status: " + this.status + " (expected: " + expected + ")");
        }
    }
}
