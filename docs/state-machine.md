# Notification 상태 머신

본 문서는 알림 한 건이 시스템 내에서 거치는 생애주기와 전이 규칙을 정의한다.

---

## 1. 상태 다이어그램

```mermaid
stateDiagram-v2
    [*] --> PENDING: API 요청

    PENDING --> PROCESSING: 워커 클레임\n(조건부 UPDATE)

    PROCESSING --> SENT: 발송 성공
    PROCESSING --> FAILED: 발송 실패
    PROCESSING --> PENDING: Stuck 복구\n(claimed_at 임계값 초과)

    FAILED --> PENDING: 백오프 후 재시도\n(retry_count < max_retry)
    FAILED --> DEAD_LETTER: 최대 재시도 초과

    DEAD_LETTER --> PENDING: 운영자 수동 재시도\n(API)

    SENT --> [*]
    DEAD_LETTER --> [*]: (수동 재시도 없으면 종착)
```

---

## 2. 상태 정의

| 상태 | 의미 | 진입 시점 |
|------|------|----------|
| **PENDING** | 발송 대기 중 (워커가 가져갈 수 있음) | API로 알림 등록 시 / FAILED에서 재시도 / DEAD_LETTER에서 수동 재시도 / PROCESSING이 Stuck 복구됨 |
| **PROCESSING** | 워커가 클레임해 처리 중 | 워커가 PENDING을 가져갈 때 |
| **SENT** | 발송 완료 (종착 상태) | 워커가 발송 성공 시 |
| **FAILED** | 발송 실패 (재시도 대기 또는 DEAD_LETTER 전이) | 워커가 발송 실패 시 |
| **DEAD_LETTER** | 최대 재시도 초과 (운영자 개입 필요) | retry_count ≥ max_retry 도달 시 |

---

## 3. 상태 전이 규칙

### 허용 전이

| # | 현재 상태 | 다음 상태 | 트리거 | 기록되는 정보 |
|---|----------|----------|--------|-------------|
| 1 | (초기) | PENDING | API 알림 등록 | created_at, payload 등 |
| 2 | PENDING | PROCESSING | 워커 클레임 | worker_id, claimed_at |
| 3 | PROCESSING | SENT | 발송 성공 | sent_at, title, body 렌더 결과 |
| 4 | PROCESSING | FAILED | 발송 실패 (예외) | last_error, failed_at, retry_count++ |
| 5 | PROCESSING | PENDING | Stuck 복구 (Reaper) | worker_id, claimed_at 리셋 |
| 6 | FAILED | PENDING | 백오프 시간 경과 + retry_count < max_retry | next_attempt_at 갱신 |
| 7 | FAILED | DEAD_LETTER | retry_count ≥ max_retry | failed_at |
| 8 | DEAD_LETTER | PENDING | 운영자 수동 재시도 API | retry_count 정책에 따라 |

### 금지 전이 (시도 시 예외)

- `SENT → *` : 종착 상태, 어떤 전이도 불허
- `PROCESSING → DEAD_LETTER` : 단순화 — FAILED를 거쳐야 함
- `PENDING → SENT` : PROCESSING을 거쳐야 함
- `DEAD_LETTER → PENDING` (자동) : 자동 전이는 없음, 수동 API로만 허용

---

## 4. 핵심 시나리오별 흐름

### 시나리오 A: 정상 발송

```
[API 요청] → PENDING
[워커 클레임] → PROCESSING
[발송 성공] → SENT (종착)
```

### 시나리오 B: 일시 실패 후 성공

```
[API 요청] → PENDING
[워커 클레임 1차] → PROCESSING → [SMTP 일시 장애] → FAILED (retry_count=1)
   ↓ 백오프 대기
[next_attempt_at 도달] → PENDING
[워커 클레임 2차] → PROCESSING → [발송 성공] → SENT
```

### 시나리오 C: 영구 실패 (DEAD_LETTER)

```
[API 요청] → PENDING → PROCESSING → FAILED (retry_count=1)
   → 백오프 → PENDING → PROCESSING → FAILED (retry_count=2)
   → ... 반복 ...
   → PROCESSING → FAILED (retry_count=5 = max_retry)
   → DEAD_LETTER (운영자 개입 필요)
```

### 시나리오 D: 워커 크래시 (Stuck 복구)

```
[API 요청] → PENDING → PROCESSING (worker_id=W1, claimed_at=10:00)
[워커 W1 크래시 — claimed_at 그대로 멈춤]
[Reaper가 10:05에 스캔] → claimed_at가 임계값(5분) 초과
[PROCESSING → PENDING 복구, worker_id 리셋]
[다른 워커가 다시 처리] → ...
```

### 시나리오 E: 운영자 수동 재시도

```
[알림이 DEAD_LETTER로 종착]
[운영자가 admin API 호출: POST /admin/notifications/{id}/retry]
[DEAD_LETTER → PENDING, retry_count는 정책에 따라 초기화 또는 유지]
[정상 흐름으로 복귀]
```

---

## 5. 구현 방식: 도메인 메서드

상태 머신은 **Java 도메인 메서드 + enum**으로 구현한다. Spring StateMachine 같은 외부 라이브러리는 사용하지 않는다.

```java
public enum NotificationStatus {
    PENDING, PROCESSING, SENT, FAILED, DEAD_LETTER
}

@Entity
public class Notification {
    private NotificationStatus status;

    public void startProcessing(String workerId) {
        if (status != PENDING) {
            throw new IllegalStateTransitionException(status, PROCESSING);
        }
        this.status = PROCESSING;
        this.workerId = workerId;
        this.claimedAt = LocalDateTime.now();
    }

    public void markAsSent() { /* PROCESSING → SENT */ }
    public void markAsFailed(String reason) { /* PROCESSING → FAILED */ }
    public void scheduleRetry(LocalDateTime nextAttempt) { /* FAILED → PENDING */ }
    public void moveToDeadLetter() { /* FAILED → DEAD_LETTER */ }
    public void recoverFromStuck() { /* PROCESSING → PENDING (Reaper) */ }
    public void manualRetry() { /* DEAD_LETTER → PENDING */ }
}
```

→ 잘못된 전이 시도는 `IllegalStateTransitionException`으로 즉시 차단.

---

## 6. 읽음 처리 (별도 차원)

알림의 "**발송 상태**" 와 "**사용자 읽음**" 은 **별도 차원**이다.

- `status`: 시스템의 발송 라이프사이클 (PENDING~DEAD_LETTER)
- `read_at`: 사용자가 알림 페이지 조회 시 기록되는 timestamp (nullable)

EMAIL은 보통 read_at이 NULL 유지 (외부 클라이언트에서 읽기 때문에 추적 불가). IN_APP은 사용자가 알림 페이지를 열거나 명시적으로 읽음 처리 시 기록된다.

→ 자세한 근거는 [design-decisions.md](./design-decisions.md#읽음-처리-분리) 참조.

---

## 7. 운영 파라미터

| 파라미터 | 기본값 | 비고 |
|---------|--------|------|
| `max_retry` | 5 | Notification 컬럼, 알림별 override 가능 |
| Stuck 임계값 | 5분 | claimed_at으로부터 |
| Reaper 주기 | 1분 | @Scheduled |
| 워커 폴링 주기 | 1초 | @Scheduled |
| 재시도 백오프 | 지수 (1m, 5m, 30m, ...) | RetryPolicy 클래스 |

→ Phase 7, 8 구현 시 상세 결정.
