package com.chanudevelop.notification.application;

import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.NotificationTemplate;
import com.chanudevelop.notification.domain.dispatcher.NotificationDispatcher;
import com.chanudevelop.notification.domain.dispatcher.RenderedMessage;
import com.chanudevelop.notification.domain.template.TemplateRenderer;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import com.chanudevelop.notification.infrastructure.persistence.NotificationTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 워커가 호출하는 "한 알림 처리 책임자".
 *
 * <p>두 트랜잭션 메서드로 분리 (ADR-012 Sub-6):
 * <ul>
 *   <li>{@link #claimPending(String)}: 폴링 + PROCESSING 전이 (짧은 트랜잭션, DB만)</li>
 *   <li>{@link #processOne(UUID)}: 렌더 + 발송 + SENT/FAILED (알림 1건당 별도 트랜잭션)</li>
 * </ul>
 *
 * <p><b>중요</b>: 묶음 호출은 외부 워커(Step 6-6 NotificationProcessor)에서 수행한다.
 * 같은 클래스 안 self-invocation은 Spring AOP proxy 특성상 {@code @Transactional}이
 * 적용되지 않는 함정이 있다 (TS-001 참조).
 */
@Slf4j
@Service
public class NotificationProcessingService {

    private static final String DEFAULT_LOCALE = "ko_KR";

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;
    private final Map<NotificationChannel, NotificationDispatcher> dispatchers;
    private final int batchSize;

    public NotificationProcessingService(
            NotificationRepository notificationRepository,
            NotificationTemplateRepository templateRepository,
            TemplateRenderer templateRenderer,
            List<NotificationDispatcher> dispatcherList,
            @Value("${notification.worker.batch-size}") int batchSize
    ) {
        this.notificationRepository = notificationRepository;
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
        this.batchSize = batchSize;
        this.dispatchers = new EnumMap<>(NotificationChannel.class);
        for (NotificationDispatcher dispatcher : dispatcherList) {
            this.dispatchers.put(dispatcher.supportedChannel(), dispatcher);
        }
        log.info("NotificationProcessingService initialized: batchSize={}, dispatchers={}",
                batchSize, this.dispatchers.keySet());
    }

    /**
     * PENDING 알림을 batchSize만큼 잡아서 PROCESSING으로 전이.
     *
     * <p>{@code SELECT FOR UPDATE SKIP LOCKED}로 다중 워커 인스턴스에서도 안전.
     * 트랜잭션이 짧게 끝나면서 PROCESSING 상태로 즉시 commit → 다른 워커가 같은 알림 안 잡음.
     *
     * @param workerId 호출 워커 식별자
     * @return 클레임된 알림 ID 목록 (외부에서 processOne으로 순회 처리)
     */
    @Transactional
    public List<UUID> claimPending(String workerId) {
        List<Notification> candidates = notificationRepository.findPendingForUpdate(batchSize);
        candidates.forEach(n -> n.startProcessing(workerId));
        List<UUID> ids = candidates.stream().map(Notification::getId).toList();
        if (!ids.isEmpty()) {
            log.info("[CLAIM] workerId={}, claimed={} ids={}", workerId, ids.size(), ids);
        }
        return ids;
    }

    /**
     * 클레임된 알림 1건을 처리한다.
     *
     * <p>흐름: 템플릿 조회 → 렌더 → Dispatcher 호출 → markAsSent.
     * 실패 시 try-catch에서 markAsFailed로 같은 트랜잭션에서 FAILED 저장.
     *
     * @param notificationId 처리할 알림 ID
     */
    @Transactional
    public void processOne(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("Notification not found: " + notificationId));

        try {
            // 1) channel별 전용 템플릿 우선 → 2) 없으면 channel=NULL 공통 템플릿 fallback
            NotificationTemplate template = templateRepository
                    .findFirstByTypeAndLocaleAndChannelAndActiveTrue(
                            notification.getType(), DEFAULT_LOCALE, notification.getChannel())
                    .or(() -> templateRepository
                            .findFirstByTypeAndLocaleAndChannelIsNullAndActiveTrue(
                                    notification.getType(), DEFAULT_LOCALE))
                    .orElseThrow(() -> new IllegalStateException(
                            "Template not found: type=" + notification.getType()
                                    + ", channel=" + notification.getChannel()
                                    + ", locale=" + DEFAULT_LOCALE));

            RenderedMessage rendered = templateRenderer.render(template, notification.getPayload());

            NotificationDispatcher dispatcher = dispatchers.get(notification.getChannel());
            if (dispatcher == null) {
                throw new IllegalStateException(
                        "No dispatcher registered for channel: " + notification.getChannel());
            }

            dispatcher.dispatch(notification, rendered);
            notification.markAsSent(rendered.title(), rendered.body());

            log.info("[PROCESS] id={} → SENT", notificationId);
        } catch (Exception e) {
            log.warn("[PROCESS] id={} → FAILED: {}", notificationId, e.getMessage());
            notification.markAsFailed(e.getMessage());
        }
    }
}
