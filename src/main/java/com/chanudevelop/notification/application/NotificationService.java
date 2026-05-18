package com.chanudevelop.notification.application;

import com.chanudevelop.notification.application.dto.NotificationCreateRequest;
import com.chanudevelop.notification.application.dto.NotificationDetailResponse;
import com.chanudevelop.notification.application.dto.NotificationListItem;
import com.chanudevelop.notification.application.dto.NotificationResponse;
import com.chanudevelop.notification.application.dto.PageResponse;
import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.exception.NotificationNotFoundException;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /**
     * 알림 등록 (Optimistic INSERT 패턴).
     *
     * <p>같은 idempotency_key로 동시에 N번 호출돼도 DB UNIQUE 제약이 1건만 허용한다.
     * 충돌 시 DataIntegrityViolationException을 잡아 기존 행을 반환한다.
     *
     * <p>참고: 메서드 레벨 {@code @Transactional}을 일부러 두지 않는다.
     * JpaRepository의 save/find 메서드가 각자 자체 트랜잭션을 열기 때문에:
     * <ol>
     *   <li>save가 자체 트랜잭션에서 commit 시도 → UNIQUE 위반 → 트랜잭션 rollback + 예외 propagate</li>
     *   <li>catch에서 findByIdempotencyKey가 새 트랜잭션을 열고 기존 행 조회 가능</li>
     * </ol>
     * 외부에 {@code @Transactional}을 붙이면 catch 안에서 SELECT가 abort된 트랜잭션 안에서 실패한다.
     *
     * @see com.chanudevelop.notification.domain.Notification#create
     */
    public NotificationResponse create(NotificationCreateRequest request) {
        Notification candidate = Notification.create(
                request.recipientId(),
                request.type(),
                request.channel(),
                request.referenceId(),
                request.payload()
        );

        try {
            Notification saved = notificationRepository.saveAndFlush(candidate);
            log.debug("notification created: id={}, key={}",
                    saved.getId(), saved.getIdempotencyKey());
            return NotificationResponse.of(saved, false);

        } catch (DataIntegrityViolationException e) {
            Notification existing = notificationRepository
                    .findByIdempotencyKey(candidate.getIdempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "UNIQUE violation occurred but existing notification not found: "
                                    + candidate.getIdempotencyKey(), e));

            log.debug("notification duplicate detected: existing id={}, key={}",
                    existing.getId(), existing.getIdempotencyKey());
            return NotificationResponse.of(existing, true);
        }
    }

    /**
     * 알림 상세 조회. 없으면 NotificationNotFoundException 발생 (GlobalExceptionHandler가 404로 변환).
     */
    @Transactional(readOnly = true)
    public NotificationDetailResponse getDetail(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));
        return NotificationDetailResponse.of(notification);
    }

    /**
     * 본인 알림 목록 조회 (ADR-011 비즈니스 룰).
     *
     * <p>"받은 알림" = {@code status = SENT} + {@code channel = IN_APP}.
     * <p>읽음 필터:
     * <ul>
     *   <li>{@code read=null}: 전체</li>
     *   <li>{@code read=true}: 읽은 것만 (readAt IS NOT NULL)</li>
     *   <li>{@code read=false}: 안 읽은 것만 (readAt IS NULL)</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public PageResponse<NotificationListItem> getMyNotifications(
            String recipientId, Boolean read, Pageable pageable) {
        Page<Notification> page = notificationRepository
                .findReceivedInAppByRecipient(recipientId, read, pageable);
        return PageResponse.of(page, NotificationListItem::of);
    }
}
