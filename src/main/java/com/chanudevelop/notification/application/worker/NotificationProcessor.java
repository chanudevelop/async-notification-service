package com.chanudevelop.notification.application.worker;

import com.chanudevelop.notification.application.NotificationProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

/**
 * 알림 발송 워커 본체 (@Scheduled 기반).
 *
 * <p>1초마다 자동으로 깨어나서 NotificationProcessingService를 호출한다.
 * 본 클래스는 "언제 호출할지"만 책임지고, 비즈니스 로직은 ProcessingService에 위임.
 *
 * <p>흐름:
 * <pre>
 * 1. claimPending(workerId): PENDING 후보들을 PROCESSING으로 전이 (짧은 트랜잭션)
 * 2. 빈 결과면 즉시 종료, 다음 주기까지 대기
 * 3. 클레임된 ID 각각에 processOne(id) 호출 (알림 1건당 별도 트랜잭션)
 * </pre>
 *
 * <p>workerId는 호스트명 + UUID 짧은 prefix로 구성하여 운영에서 추적 가능.
 * 같은 호스트에서 재시작 시에도 새 UUID prefix로 인스턴스 식별.
 *
 * <p>self-invocation 함정 회피: ProcessingService의 두 트랜잭션 메서드를
 * 외부 클래스(본 클래스)에서 호출하므로 Spring AOP proxy가 정상 적용됨 (TS-001 참조).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationProcessor {

    private final NotificationProcessingService processingService;
    private final String workerId;

    public NotificationProcessor(NotificationProcessingService processingService) {
        this.processingService = processingService;
        this.workerId = buildWorkerId();
        log.info("NotificationProcessor initialized: workerId={}", this.workerId);
    }

    /**
     * 폴링 메서드. fixedDelay 방식으로 이전 작업 종료 후 설정된 ms만큼 대기 후 다시 호출.
     *
     * <p>주기는 application.yaml의 {@code notification.worker.polling-interval-ms} 값으로 외부화.
     *
     * <p>예외가 메서드 밖으로 던져지면 Spring TaskScheduler가 이후 호출을 중단할 수 있어
     * 모든 예외를 내부에서 catch하여 로깅만 하고 다음 주기를 보장.
     */
    @Scheduled(fixedDelayString = "${notification.worker.polling-interval-ms}")
    public void poll() {
        try {
            List<UUID> claimedIds = processingService.claimPending(workerId);
            if (claimedIds.isEmpty()) {
                return;
            }
            for (UUID id : claimedIds) {
                processingService.processOne(id);
            }
        } catch (Exception e) {
            log.error("[WORKER] poll cycle failed, will retry next interval", e);
        }
    }

    private static String buildWorkerId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostname = "unknown";
        }
        String uuidPrefix = UUID.randomUUID().toString().substring(0, 8);
        return "worker-" + hostname + "-" + uuidPrefix;
    }
}
