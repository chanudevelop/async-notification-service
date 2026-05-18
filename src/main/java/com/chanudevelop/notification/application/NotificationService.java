package com.chanudevelop.notification.application;

import com.chanudevelop.notification.application.dto.NotificationCreateRequest;
import com.chanudevelop.notification.application.dto.NotificationResponse;
import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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
        // 1. Notification 객체 생성
        Notification candidate = Notification.create(
                request.recipientId(),
                request.type(),
                request.channel(),
                request.referenceId(),
                request.payload()
        );

        try {
            // 2. 알림 저장(중복 요청 처리 고려)
            Notification saved = notificationRepository.saveAndFlush(candidate);
            log.debug("notification created: id={}, key={}",
                    saved.getId(), saved.getIdempotencyKey());
            return NotificationResponse.of(saved, false);

        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반 = 이미 같은 idempotency_key가 존재. 기존 행 반환.
            // 위 save 트랜잭션은 이미 rollback됨 → 아래 find는 새 트랜잭션에서 동작.
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
}
