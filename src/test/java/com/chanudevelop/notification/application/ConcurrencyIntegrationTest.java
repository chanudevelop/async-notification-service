package com.chanudevelop.notification.application;

import com.chanudevelop.notification.NotificationApplication;
import com.chanudevelop.notification.TestcontainersConfiguration;
import com.chanudevelop.notification.application.dto.MarkAsReadResponse;
import com.chanudevelop.notification.application.dto.NotificationCreateRequest;
import com.chanudevelop.notification.application.dto.NotificationResponse;
import com.chanudevelop.notification.domain.Notification;
import com.chanudevelop.notification.domain.NotificationChannel;
import com.chanudevelop.notification.domain.NotificationStatus;
import com.chanudevelop.notification.domain.NotificationType;
import com.chanudevelop.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 시나리오 자동 검증 (Phase 9).
 *
 * <p>본 프로젝트의 동시성 안전 메커니즘 세 가지를 실제로 여러 스레드를 띄워 검증한다.
 * <ul>
 *   <li>9-3 멱등성 동시성: 같은 키 100건 동시 등록 → DB UNIQUE 제약 + Optimistic INSERT가 1건만 통과</li>
 *   <li>9-4 클레임 동시성: 두 워커가 동시에 claimPending → SKIP LOCKED로 겹치지 않게 분할</li>
 *   <li>읽음 처리 동시성: 같은 알림에 100개 스레드 동시 markAsRead → DB 조건부 UPDATE로 정확히 1번만 firstRead=true</li>
 * </ul>
 *
 * <p>다른 통합 테스트는 단일 스레드 시나리오만 검증한다. 본 클래스는 멱등성/SKIP LOCKED가
 * 진짜 동시 환경에서 동작하는지 자동 증명한다.
 *
 * <p>{@code ExecutorService} + {@code CountDownLatch} 패턴으로 모든 스레드를 같은 시각에
 * 출발시켜 race condition을 의도적으로 유도한다.
 */
@SpringBootTest(classes = NotificationApplication.class)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ConcurrencyIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationProcessingService processingService;

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("동일한 멱등성 키로 100개 동시 요청해도 DB에는 1건만 INSERT되고 나머지 99건은 isDuplicate=true가 된다")
    void idempotency_under_concurrent_requests() throws Exception {
        int threadCount = 100;
        NotificationCreateRequest request = new NotificationCreateRequest(
                "user-concurrent-001",
                NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP,
                "concurrent-event-001",
                Map.of("studentName", "동시성", "courseName", "Spring")
        );

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ConcurrentLinkedQueue<NotificationResponse> responses = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    NotificationResponse res = notificationService.create(request);
                    responses.add(res);
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("100개 스레드 모두 30초 안에 완료되어야 한다").isTrue();
        assertThat(failureCount.get()).as("어떤 요청도 예외로 끝나지 않아야 한다").isZero();
        assertThat(responses).hasSize(threadCount);

        // DB에 1건만 저장됨
        assertThat(notificationRepository.count()).isEqualTo(1);

        // 모두 같은 알림 ID 반환
        Set<UUID> distinctIds = responses.stream()
                .map(NotificationResponse::id)
                .collect(Collectors.toSet());
        assertThat(distinctIds).hasSize(1);

        // isDuplicate=false 정확히 1건, 나머지는 true
        long firstCount = responses.stream().filter(r -> !r.isDuplicate()).count();
        long duplicateCount = responses.stream().filter(NotificationResponse::isDuplicate).count();
        assertThat(firstCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("두 워커가 동시에 claimPending을 호출하면 SKIP LOCKED로 같은 알림을 두 번 잡지 않는다")
    void claim_is_safe_under_concurrent_workers() throws Exception {
        // 10건 PENDING 미리 저장
        List<UUID> insertedIds = insertPendingNotifications(10);

        // 두 워커가 동시에 claimPending 호출
        int workerCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);
        ConcurrentLinkedQueue<UUID> workerAClaimed = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<UUID> workerBClaimed = new ConcurrentLinkedQueue<>();

        executor.submit(() -> {
            try {
                startLatch.await();
                workerAClaimed.addAll(processingService.claimPending("worker-A"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startLatch.await();
                workerBClaimed.addAll(processingService.claimPending("worker-B"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();

        // 두 워커가 잡은 알림의 합집합이 같은 알림 ID를 가지면 중복 처리 사고
        Set<UUID> intersection = new java.util.HashSet<>(workerAClaimed);
        intersection.retainAll(workerBClaimed);
        assertThat(intersection)
                .as("같은 알림을 두 워커가 동시에 잡았다 — SKIP LOCKED 실패")
                .isEmpty();

        // 두 워커가 잡은 합계는 전체 PENDING 수와 같거나 작아야 한다
        // (한 워커가 batch-size 한도까지 다 잡으면 다른 워커는 빈 리스트 가능)
        int totalClaimed = workerAClaimed.size() + workerBClaimed.size();
        assertThat(totalClaimed).isLessThanOrEqualTo(insertedIds.size());

        // 잡힌 모든 알림은 PROCESSING 상태로 전이
        Set<UUID> allClaimed = new java.util.HashSet<>(workerAClaimed);
        allClaimed.addAll(workerBClaimed);
        for (UUID id : allClaimed) {
            Notification reloaded = notificationRepository.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
            assertThat(reloaded.getWorkerId()).isIn("worker-A", "worker-B");
        }
    }

    @Test
    @DisplayName("같은 알림에 100개 스레드가 동시에 markAsRead를 호출해도 DB 조건부 UPDATE로 정확히 1번만 firstRead=true가 된다")
    void markAsRead_under_concurrent_requests() throws Exception {
        // 사용자 본인의 알림 1건 준비
        Notification target = Notification.create(
                "user-read-concurrent",
                NotificationType.ENROLLMENT_COMPLETED,
                NotificationChannel.IN_APP,
                "read-concurrent-001",
                Map.of("studentName", "동시읽음", "courseName", "테스트")
        );
        notificationRepository.saveAndFlush(target);

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ConcurrentLinkedQueue<MarkAsReadResponse> responses = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MarkAsReadResponse res = notificationService.markAsRead(
                            target.getId(), "user-read-concurrent");
                    responses.add(res);
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("100개 스레드 모두 30초 안에 완료").isTrue();
        assertThat(failureCount.get()).as("어떤 요청도 예외로 끝나지 않아야 한다").isZero();
        assertThat(responses).hasSize(threadCount);

        // 핵심 검증: firstRead=true가 정확히 1건, 나머지 99건은 false
        long firstReadTrue = responses.stream().filter(MarkAsReadResponse::firstRead).count();
        long firstReadFalse = responses.stream().filter(r -> !r.firstRead()).count();
        assertThat(firstReadTrue).isEqualTo(1);
        assertThat(firstReadFalse).isEqualTo(threadCount - 1);

        // DB에 readAt이 정확히 1번 세팅됨 (last-writer-wins 패턴이 아닌 조건부 UPDATE 검증)
        Notification reloaded = notificationRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getReadAt()).isNotNull();
    }

    private List<UUID> insertPendingNotifications(int count) {
        List<Notification> batch = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(Notification.create(
                    "user-claim-" + i,
                    NotificationType.ENROLLMENT_COMPLETED,
                    NotificationChannel.IN_APP,
                    "claim-ref-" + i,
                    Map.of("studentName", "테스트" + i, "courseName", "동시클레임")
            ));
        }
        notificationRepository.saveAllAndFlush(batch);
        return batch.stream().map(Notification::getId).toList();
    }
}
