# 비동기 처리 구조 및 재시도 정책

과제 추가 제출물입니다. 비동기 발송 흐름, 트랜잭션 경계, 재시도 정책, Stuck 복구를 어떻게 설계했는지 정리합니다.

---

## 전체 그림

세 개의 워커가 각자 다른 알림 상태를 담당해서 함께 동작합니다.

```
┌─────────────────────────────────────────────────────────────┐
│                      PostgreSQL DB                           │
│  notifications: status / claimed_at / next_attempt_at / ...  │
└─────────────────────────────────────────────────────────────┘
        ▲                       ▲                       ▲
        │                       │                       │
┌───────┴─────────┐    ┌────────┴────────┐    ┌─────────┴─────────┐
│ Notification    │    │ Retry           │    │ StuckReaper       │
│   Processor     │    │   Processor     │    │   Processor       │
│ (1초 폴링)      │    │ (5초 폴링)      │    │ (1분 폴링)        │
│ PENDING         │    │ FAILED          │    │ PROCESSING (갇힘) │
└─────────────────┘    └─────────────────┘    └───────────────────┘
```

각 워커가 다른 status만 보기 때문에 충돌 없이 동시 동작합니다. 한 알림이 두 워커에 동시에 잡힐 일이 없는 구조입니다.

---

## API와 발송 흐름 분리

알림 등록 API는 PENDING 상태로 DB에 저장하고 즉시 `202 Accepted`로 응답합니다. 실제 발송은 백그라운드 워커가 비동기로 처리합니다.

```
[유저] POST /notifications
   │
   ├─→ DB에 INSERT (PENDING)
   ├─→ 202 Accepted 응답  ← 유저는 여기까지만 봄
   │
   │  (비동기, 1초 이내)
   │
   ├─→ NotificationProcessor가 폴링해서 잡음
   ├─→ PROCESSING 전이
   ├─→ Template + payload로 메시지 렌더
   ├─→ Dispatcher 호출 (실제 외부 시스템 발송)
   └─→ SENT 또는 FAILED 저장
```

API 요청 스레드와 발송 스레드가 분리되어 있어, 외부 시스템(SMTP 등) 응답 시간이 5초 걸리든 30초 걸리든 사용자 API 응답 시간엔 영향이 없습니다.

---

## 트랜잭션 경계 — 두 단계 분리

`NotificationProcessingService`는 두 개의 `@Transactional` 메서드로 분리되어 있습니다.

### claimPending (트랜잭션 1)

```java
@Transactional
public List<UUID> claimPending(String workerId) {
    List<Notification> candidates = repository.findPendingForUpdate(batchSize);
    candidates.forEach(n -> n.startProcessing(workerId));
    return candidates.stream().map(Notification::getId).toList();
}
```

DB 작업만 — 폴링 + PROCESSING 전이. 매우 짧은 트랜잭션입니다.

### processOne (트랜잭션 2 — 알림 1건당 별도)

```java
@Transactional
public void processOne(UUID id) {
    Notification n = repository.findById(id).orElseThrow();
    try {
        // 템플릿 조회 → 렌더 → Dispatcher 호출
        dispatcher.dispatch(n, rendered);
        n.markAsSent(rendered.title(), rendered.body());
    } catch (Exception e) {
        n.markAsFailed(e.getMessage());
    }
}
```

외부 시스템 호출이 포함된 트랜잭션. 알림 1건마다 새 트랜잭션이라 한 건 실패가 다른 건에 영향을 주지 않습니다.

### 왜 분리했나

한 트랜잭션으로 묶으면 두 가지 문제가 생깁니다.

1. **DB 락이 외부 시스템 응답 시간만큼 잡힘**. SMTP가 5초 걸리면 락도 5초 잡혀 다른 워커가 다음 알림 처리를 못 합니다.
2. **한 건 실패가 전체 롤백을 유발**. for문 중간에 예외 던지면 앞서 처리한 알림도 다 무효.

분리하면 락은 빨리 풀려서 PROCESSING 상태가 즉시 DB에 반영되고, 다른 워커는 그 알림을 폴링 대상에서 자연스럽게 제외합니다 (status 조건이 PENDING이라).

### Spring AOP proxy self-invocation 회피

두 메서드를 묶어서 호출하는 코드는 ProcessingService 안이 아니라 외부 클래스인 `NotificationProcessor` 워커에 있습니다.

```java
// NotificationProcessor (외부 클래스)
public void poll() {
    List<UUID> claimed = processingService.claimPending(workerId);
    for (UUID id : claimed) {
        processingService.processOne(id);
    }
}
```

같은 클래스 안에서 `@Transactional` 메서드를 호출하면 Spring AOP proxy가 인터셉트하지 못해 트랜잭션이 적용되지 않습니다. Phase 4 작업 중 비슷한 함정(TS-001)을 학습한 경험을 적용해서 처음부터 외부 클래스에서 호출하는 구조로 잡았습니다.

---

## 동시성 제어 — SELECT FOR UPDATE SKIP LOCKED

다중 인스턴스 환경에서 같은 알림을 두 워커가 동시에 잡지 않도록 PostgreSQL의 `SELECT FOR UPDATE SKIP LOCKED`를 사용합니다.

### 폴링 쿼리

```sql
SELECT * FROM notifications
 WHERE status = 'PENDING'
   AND next_attempt_at <= NOW()
 ORDER BY next_attempt_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batchSize
```

### 동작 원리

```
시점: 12:00:00.000
DB에 PENDING 알림 10건 (id 1~10)

워커 A: 쿼리 실행 → id 1~5에 락 잡음 → [1, 2, 3, 4, 5] 반환
워커 B: 쿼리 실행 → 1~5는 락 걸려있음 → 건너뛰고 [6, 7, 8, 9, 10] 반환
워커 C: 쿼리 실행 → 1~10 다 락 걸림 → [] 빈 리스트 (다음 폴링까지 대기)
```

`SKIP LOCKED`가 없는 일반 `FOR UPDATE`라면 워커 B/C가 락이 풀릴 때까지 대기해서 처리량이 떨어집니다. `SKIP LOCKED`는 충돌을 "건너뛰기"로 처리해 처리량 손실을 없앱니다.

### Partial Index 활용

```sql
CREATE INDEX idx_pending_polling
    ON notifications (next_attempt_at)
    WHERE status = 'PENDING';
```

전체 알림의 99% 이상이 SENT/DEAD_LETTER 종착 상태입니다. PENDING만 인덱싱하는 Partial Index를 만들면 인덱스 크기가 100배 가깝게 줄어들어 메모리 캐시 효율과 폴링 쿼리 속도가 크게 좋아집니다.

같은 패턴이 Stuck Reaper용 `idx_stuck_reaper`에도 적용되어 있습니다.

---

## 재시도 정책 — Exponential Backoff + Jitter

발송 실패한 알림은 별도 `RetryProcessor`가 5초마다 폴링해서 재시도 일정을 잡습니다.

### 백오프 공식

```
delay = min(base × multiplier^(retryCount - 1), max) + random(0, jitter)
```

기본 파라미터 (application.yaml 외부화):
- `base = 10초`
- `multiplier = 2`
- `max = 300초` (5분 상한)
- `jitter = 0~5초`

### 계산 예시

| retryCount | base 계산 | + jitter | 최종 delay |
|-----------|----------|---------|----------|
| 1 (첫 실패) | 10 × 2^0 = 10 | 0~5 | 10~15초 |
| 2 | 10 × 2^1 = 20 | 0~5 | 20~25초 |
| 3 | 10 × 2^2 = 40 | 0~5 | 40~45초 |
| 4 | 10 × 2^3 = 80 | 0~5 | 80~85초 |
| 5 | 10 × 2^4 = 160 | 0~5 | 160~165초 |
| 6+ | retryCount >= maxRetry → DEAD_LETTER | — | — |

### 왜 Exponential — 즉시 재시도가 안 되는 이유

외부 시스템 일시 장애 시 즉시 재시도하면:

```
12:00:00  알림 100건 동시 실패 (SMTP timeout)
12:00:00.001  100건 모두 즉시 재시도 → 또 100건 실패
12:00:00.002  또 즉시 재시도 → ...
```

외부 시스템이 회복할 시간을 안 주고 부하만 가중시키는 "thundering herd" 문제입니다.

Exponential은 매 시도마다 대기를 2배로 늘려 외부 시스템에 충분한 회복 시간을 제공합니다.

### 왜 Jitter — Exponential만으로도 부족한 이유

동시에 실패한 N건이 정확히 같은 시각에 재시도되는 문제는 여전히 남습니다.

```
12:00:00  알림 100건 동시 실패
12:00:10  100건 모두 정확히 같은 순간 재시도 → 외부 시스템 또 폭주
```

Jitter는 각 알림의 재시도 시각에 0~5초 랜덤을 더해 분산합니다.

```
12:00:00  알림 100건 실패
12:00:10.0 ~ 12:00:15.0  100건이 0~5초 사이에 흩어져 재시도
```

AWS Architecture Blog의 "Exponential Backoff And Jitter" 원본 아이디어이고, AWS SDK, Resilience4j 등 표준 라이브러리도 동일 패턴입니다.

### 재시도 흐름

```
markAsFailed → status=FAILED, retryCount++ (NotificationProcessor에서)
        ↓
[RetryProcessor 5초 후]
        ↓
canBeRetried (retryCount < maxRetry) ?
   ├─ YES → 백오프 계산 → scheduleRetry(now + delay) → PENDING
   │         └─ 시간 경과 후 NotificationProcessor가 다시 폴링해 처리
   │
   └─ NO  → moveToDeadLetter → DEAD_LETTER (자동 처리 종료)
```

`scheduleRetry`는 status를 PENDING으로 되돌리고 `next_attempt_at`을 미래 시각으로 갱신합니다. retry_count는 그대로 유지 — 다음 백오프 계산에 누적된 카운트를 사용해야 하므로.

---

## Stuck 복구

워커 프로세스가 PENDING을 PROCESSING으로 잡은 직후 죽으면 그 알림이 영원히 PROCESSING에 갇히는 문제가 있습니다 (분산 시스템에서 Pod evicted, OOM, 서버 재시작 등으로 자주 발생).

### Reaper 동작

```
[StuckReaperProcessor 1분마다]
        ↓
SELECT * FROM notifications
 WHERE status = 'PROCESSING'
   AND claimed_at < NOW() - 5분
        ↓
recoverFromStuck() 호출
   → status = PENDING
   → worker_id = null
   → claimed_at = null
        ↓
NotificationProcessor가 다시 폴링해 잡음 → 재발송
```

### 임계값 5분 — 왜 이 값인가

| 임계값 | 결과 |
|--------|------|
| 30초 | 정상 발송이 30초 넘으면 살아있는 워커의 작업을 가로채 중복 발송 사고 |
| **5분** | 외부 시스템 timeout(30초~2분) 안전 마진 충분 |
| 1시간 | 죽은 워커의 알림이 1시간 지연 — 사용자 체감 ↓ |

운영에선 정상 발송 P99를 측정해서 그 2~3배로 조정해야 하는 값이라 `application.yaml`에 외부화했습니다.

### retry_count를 안 건드리는 이유

`recoverFromStuck`은 의도적으로 retry_count를 변경하지 않습니다. Stuck은 워커 프로세스가 죽은 인프라 사고지 외부 시스템 사고가 아니기 때문입니다.

만약 retry_count를 증가시키면 인프라가 불안정한 시기에 stuck이 누적되다 DEAD_LETTER로 보내져, 외부 시스템 한 번도 거치지 않은 알림이 영구 실패 처리되는 사고가 납니다. 외부 시스템 실패는 markAsFailed에서만 retry_count를 증가시키고, 인프라 사고는 카운트 무관하게 재처리하는 정책 분리입니다.

---

## 한 알림의 전체 라이프사이클

상태 머신 자세히는 [state-machine.md](state-machine.md)에 있습니다. 비동기 흐름 관점에서 정리하면:

### 정상 발송
```
PENDING → PROCESSING → SENT (종착)
```

### 한 번 실패 후 성공
```
PENDING → PROCESSING → FAILED
         ↓ (5초 후 RetryProcessor)
PENDING (next_attempt_at = +12초) → PROCESSING → SENT
```

### 5회 실패 후 DEAD_LETTER
```
PENDING → PROCESSING → FAILED (retry=1)
PENDING (+12초) → PROCESSING → FAILED (retry=2)
... 반복 ...
PENDING → PROCESSING → FAILED (retry=5)
         ↓ (RetryProcessor: retry == max → moveToDeadLetter)
DEAD_LETTER (종착, 운영자 수동 재시도 대상)
```

### Stuck 발생 → 복구
```
PENDING → PROCESSING (워커 A가 잡고 발송 시작)
                ↓
            워커 A 죽음
                ↓
         PROCESSING에 5분 갇힘
                ↓ (Reaper 1분 폴링)
PENDING (worker_id/claimed_at 리셋) → PROCESSING → SENT
```

---

## 실제 운영 환경 전환 시 변경 포인트

본 과제는 평가 대상이지 운영 시스템은 아니라 몇 군데를 Mock 또는 단순화로 처리했습니다. 운영 환경 전환 시 어디를 바꾸면 되는지 정리합니다.

### 1. Dispatcher Mock → 실제 클라이언트

현재는 `EmailDispatcher`, `InAppDispatcher`가 콘솔 로그만 찍습니다. 운영 환경에선:

- `EmailDispatcher`: SMTP 클라이언트(JavaMail, Spring Mail) 또는 SaaS(SES, SendGrid) 호출
- `InAppDispatcher`: 푸시 알림 통합 시 FCM/APNs 호출 추가 가능

인터페이스 `NotificationDispatcher`는 그대로 유지. ProcessingService, Worker 등 호출 측 코드는 한 줄도 안 바꿔도 됩니다 (OCP).

### 2. 설정값 튜닝

`notification.*` 설정을 운영 트래픽 기준으로 조정:

```yaml
notification:
  worker:
    batch-size: 10              # 운영 측정 후 트래픽에 맞게
    polling-interval-ms: 1000   # 트래픽 폭증 시 줄이거나 늘리거나
  retry:
    base-delay-seconds: 10      # 외부 시스템 회복 시간 분석 후 조정
    max-delay-seconds: 300
  reaper:
    stuck-threshold-seconds: 300   # 정상 발송 P99 측정 후 그 2~3배로
```

### 3. 다중 인스턴스 환경에서 @Scheduled

Spring `@Scheduled`는 인스턴스마다 독립적으로 트리거됩니다. 본 과제 구조는 SKIP LOCKED로 중복 처리를 막아서 다중 워커가 모두 동작해도 안전합니다.

만약 "오직 한 인스턴스만 워커 실행"이 필요한 경우 (예: Reaper는 1대만 띄우고 싶음) ShedLock 같은 distributed lock 라이브러리를 도입할 수 있습니다. 본 과제에선 모든 인스턴스가 워커 동작해도 안전한 구조라 ShedLock 미도입.

### 4. 모니터링/메트릭

현재는 Actuator 기본 헬스체크만 있습니다. 운영 환경에선:

- Prometheus 메트릭 통합 (`micrometer-registry-prometheus`)
- 핵심 지표: PENDING/FAILED/DEAD_LETTER 누적 수, 워커 처리 속도, 백오프 시도 횟수 분포
- Stuck 발생 알람 (Reaper가 잡은 건수 = 인프라 사고 빈도 지표)

### 5. 인증

`X-User-Id` 헤더는 본 과제 한정 간략화입니다. 운영은 JWT/SecurityContext 같은 표준 인증 + 사용자별 권한 검사 필요.

### 6. 메시지 브로커 통합

과제 요구사항 "실제 운영 환경으로 전환 가능한 구조"를 의식해서 ProcessingService를 Dispatcher와 분리해 두었습니다.

미래에 Kafka 통합 시:
- API → DB INSERT + Kafka 발송 이벤트 (Outbox Pattern)
- 워커가 Kafka consumer로 동작 (현재 `@Scheduled` 폴링을 Kafka consume으로 대체)
- ProcessingService/Dispatcher 인터페이스는 그대로 유지

본 과제는 메시지 브로커 미설치라 DB 폴링 방식으로 구현했지만, 동일 도메인 로직이 Kafka 환경에서도 그대로 동작할 수 있는 구조입니다.

---

## 트레이드오프 정리

설계 과정에서 고려했지만 본 과제 범위에 안 넣은 것들입니다.

| 미적용 | 이유 |
|------|------|
| ShedLock (`@Scheduled` 중복 실행 방지) | 본 구조는 모든 워커가 동작해도 SKIP LOCKED로 안전 |
| Spring Retry / Resilience4j | 백오프 + 재시도 로직이 단순해서 직접 구현이 더 명확. 라이브러리는 본 과제 규모에 과함 |
| Circuit Breaker | 본 과제 평가 항목 아님. Phase 7 백오프로 thundering herd는 막힘 |
| Outbox Pattern | 메시지 브로커 미사용이라 불필요. Kafka 도입 시 추가 |
| Stuck Heartbeat (워커가 주기적으로 last_heartbeat_at 갱신) | 본 과제는 단순 임계값 기반이 충분. 정밀 추적 필요 시 보강 가능 |

> 참고: Read 동시성(markAsRead)은 처음엔 미적용으로 분류했다가 선택 구현으로 추가했습니다. `PATCH /notifications/{id}/read` API + Repository의 DB 조건부 UPDATE(`WHERE read_at IS NULL`)로 알림 등록 멱등성과 같은 "DB가 단일 진실 원천" 패턴을 적용. 자세한 결정 근거는 [design-decisions.md](design-decisions.md) "10. 읽음 처리 동시성" 참조.

---

자세한 결정 근거는 [design-decisions.md](design-decisions.md)에, 요구사항 해석은 [requirements-interpretation.md](requirements-interpretation.md)에 있습니다.
