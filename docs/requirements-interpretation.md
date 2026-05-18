# 요구사항 해석 및 개선 의견

과제 추가 제출물입니다. 과제 요구사항을 어떻게 해석했는지, 모호한 부분은 어떤 가정을 세웠는지, 운영 환경 관점에서 개선하고 싶은 점은 무엇인지 정리합니다.

---

## 1. 요구사항 해석

### "수신자 기준 알림 목록 (읽음/안읽음 필터 포함)"

처음엔 `WHERE recipient_id = ?` 단순 매핑으로 구현했습니다. 그런데 통합 테스트를 작성하며 사용자 관점에서 결과를 들여다보니 PENDING/FAILED/DEAD_LETTER 같은 내부 상태 알림이 같이 노출되는 게 어색했습니다. "신청 완료 알림이 왔다는데 왜 아직 안 도착했지?" 같은 혼란이 생길 수 있다고 생각했습니다.

다시 보니 "받은 알림"이라는 도메인 용어가 두 가지를 함축하고 있다고 해석했습니다.

- **`status = SENT`**: 발송이 완료된 알림만. 진행 중 상태는 사용자가 볼 일 X.
- **`channel = IN_APP`**: 같은 요구사항 안에 "읽음/안읽음 필터"가 있는데, EMAIL은 읽음 추적이 불가능하니 이 필터는 본질적으로 IN_APP을 전제로 합니다.

→ Repository 쿼리에 두 조건을 명시했습니다 (ADR-011 / TS-003).

```java
@Query("""
    SELECT n FROM Notification n
    WHERE n.recipientId = :recipientId
      AND n.status = SENT
      AND n.channel = IN_APP
      AND (:read IS NULL
           OR (:read = TRUE AND n.readAt IS NOT NULL)
           OR (:read = FALSE AND n.readAt IS NULL))
    ORDER BY n.createdAt DESC
""")
```

### "동일 이벤트에 대해 알림이 중복 발송되어서는 안 됨"

"동일 이벤트"를 어떻게 정의할지가 핵심 결정이었습니다.

- 단순히 `(recipientId, type)`만으로 잡으면 같은 사용자에게 같은 타입의 다른 이벤트(다른 강의 신청)가 모두 동일 처리되는 문제.
- 너무 세분화하면 멱등성 보장이 약해짐.

→ `(recipientId, type, referenceId, channel)` 4개 조합으로 멱등성 키를 만들었습니다.

- `referenceId`: 비즈니스 이벤트 식별자 (예: `enrollment-123`)
- `channel`: 같은 이벤트라도 EMAIL과 IN_APP 동시 발송은 별개로 처리

콜론 구분 문자열로 만들어 idempotency_key 컬럼에 저장, DB UNIQUE 제약. INSERT 시도 → UNIQUE 위반 예외 catch → 기존 알림 ID 반환하는 Optimistic INSERT 패턴 사용 (ADR-008).

### "처리 중 상태가 일정 시간 이상 지속되는 경우 복구"

"일정 시간"을 5분으로 잡았습니다.

근거: 외부 시스템(SMTP, HTTP API) 일반 timeout이 30초~2분 범위. 정상 발송이 5분을 넘는 경우는 거의 없으니 5분 넘어가면 "이건 분명히 워커가 죽었거나 비정상 상태"라고 가정하였습니다.

너무 짧게 잡으면 (예: 30초) 살아 있는 워커의 작업을 Reaper가 가로채 같은 알림을 두 번 발송하는 이슈가 발생할 수 있습니다.  너무 길게 잡으면 (예: 1시간) 죽은 워커의 알림이 1시간 동안 처리 안 돼 사용자 체감이 떨어집니다.

운영에선 정상 발송의 시간을 측정해서(P99) 그 2~3배로 조정해야 할 값이라 `application.yaml`에 외부화했습니다 (`notification.reaper.stuck-threshold-seconds: 300`).

### "다중 인스턴스 환경에서도 동일 알림이 중복 처리되어서는 안 됨"

**운영 환경 가정으로 다중 인스턴스 환경에서도 안전한 코드를 짜는 게 실무적인 생각**이라고 해석했습니다.

세 워커 모두 PostgreSQL `SELECT FOR UPDATE SKIP LOCKED` 패턴을 사용해서:
- 한 워커가 행에 락을 잡으면 다른 워커는 대기 없이 건너뜀
- 락 충돌이 처리량 손실로 이어지지 않음
- 단일 인스턴스에서도, 인스턴스 N대에서도 동일하게 안전

자세한 동작 원리는 [async-design.md](async-design.md) 참조.

### "비동기 처리 구조 — API 요청 스레드와 분리"

API 등록은 PENDING 상태로 DB INSERT 후 즉시 `202 Accepted` 응답합니다. 실제 발송은 `@Scheduled` 워커가 백그라운드에서 처리합니다.

`@Async` 메서드 호출 대신 `@Scheduled` 폴링 방식을 고른 이유:

- `@Async`는 인스턴스 안에서만 비동기. 인스턴스 죽으면 처리 중 작업 손실.
- `@Scheduled` + DB 폴링은 어떤 인스턴스든 DB의 PENDING을 보고 처리. 인스턴스가 죽어도 다른 인스턴스가 잡음 (단, PROCESSING 갇힌 건  Reaper가 복구).

과제 요구사항 "재시작 후 미처리 알림이 유실 없이 재처리" + "실제 메시지 브로커 없이 구현하되, 운영 환경 전환 가능한 구조"에 둘 다 부합하는 선택입니다.

### "실제 메시지 브로커 없이 구현하되, 운영 환경으로 전환 가능한 구조"

ProcessingService 안에서 폴링/렌더/발송 로직을 분리하고 Dispatcher 인터페이스로 채널을 추상화한 게 이 요구사항을 의식한 설계입니다.

Kafka 등 메시지 브로커 도입 시 변경 포인트:
- API → DB INSERT + Kafka publish (Outbox Pattern으로 일관성 보장)
- 워커가 `@Scheduled` 폴링 대신 Kafka consumer로 동작
- ProcessingService.processOne, Dispatcher 인터페이스는 그대로 유지

자세한 운영 환경 전환 포인트는 [async-design.md](async-design.md)의 마지막 섹션.

---

## 2. 명시되지 않은 가정

과제 원문에 명시되지 않아 직접 결정한 항목들입니다.

### 재시도 정책 — Exponential Backoff + Jitter

과제는 "재시도 가능"만 명시. 간격이나 횟수는 자유. 즉시 재시도하면 thundering herd 사고가 나서 Exponential Backoff + Jitter를 적용했습니다.

| 파라미터 | 값 |
|---------|-----|
| base | 10초 |
| multiplier | 2 |
| max | 300초 (5분) |
| jitter | 0~5초 랜덤 |
| 최대 재시도 | 5회 |

5회 초과 시 DEAD_LETTER로 격리. AWS SDK / Resilience4j와 동일한 표준 패턴.

자세한 공식과 시나리오는 [async-design.md](async-design.md)의 재시도 정책 섹션.

### Locale 다국어

한국어 단일 가정. 시드 템플릿은 `ko_KR`만 포함합니다. 다국어 지원 시 사용자 정보에서 locale을 받아오는 방식으로 확장 가능하도록 `notification_templates` 테이블에 `locale` 컬럼은 미리 두었습니다.

### 인증

본격 인증 시스템 대신 `X-User-Id` 헤더로 간략화했습니다. 운영 환경은 JWT/SecurityContext 같은 표준 인증을 통과해야 합니다.

### 알림 종류 (NotificationType)

과제 시나리오에서 언급된 이벤트들을 enum으로 정의:
- `ENROLLMENT_COMPLETED` (수강 신청 완료)
- `ENROLLMENT_CANCELLED` (수강 취소)
- `PAYMENT_CONFIRMED` (결제 완료)
- `CLASS_STARTS_TOMORROW` (강의 시작 알림)

각 타입에 해당하는 시드 템플릿(V2 마이그레이션)도 포함.

### 템플릿 변수 치환 방식

`{{variable}}` Mustache 스타일. 외부 라이브러리 추가 없이 `String.replace`로 단순 치환. 본 과제 템플릿은 조건문/반복문 같은 복잡한 기능이 필요 없어 충분합니다.

payload에 변수가 누락된 경우 즉시 예외 → markAsFailed (Fail Fast). 빈 문자열로 조용히 대체하면 깨진 메시지가 사용자에게 도달할 수 있어 명시적 실패가 안전합니다.

---

## 3. 개선 의견

운영 환경 가정에서 추가하면 좋을 항목들입니다.

### 3.1 ShedLock — Reaper 분산 락

현재 다중 인스턴스 환경에서 `StuckReaperProcessor`가 모든 인스턴스에서 동시 트리거됩니다. SKIP LOCKED로 충돌은 안 나지만, 같은 작업을 여러 인스턴스가 시도해 약간의 자원 낭비.

ShedLock 도입 시 "정확히 한 인스턴스만 Reaper 실행" 보장 가능. 발송 워커는 처리량을 위해 모든 인스턴스가 동작해야 하지만 Reaper는 1대만 돌아도 충분합니다.

본 과제 미도입 이유: 라이브러리 추가가 과제 범위 대비 무거움. SKIP LOCKED가 충돌은 막아주고 있어 운영 안전성엔 영향 X.

### 3.2 Outbox Pattern + Kafka 통합

현재는 DB 폴링 방식이지만, 처리량이 더 필요해지면 메시지 브로커 통합이 필요합니다. Outbox Pattern으로 DB 트랜잭션과 Kafka publish의 일관성을 보장하면서 점진적으로 전환 가능합니다.

```
[현재] POST → DB INSERT (PENDING) → @Scheduled 폴링 → 발송
[Outbox] POST → DB INSERT (PENDING + outbox 이벤트) → Kafka Producer → consumer → 발송
```

`@Scheduled` 폴링 워커는 Outbox 이벤트 발행 실패 케이스의 fallback으로 유지하면 좋습니다.

### 3.3 Circuit Breaker

외부 시스템(SMTP)이 완전 장애 상태일 때 매 재시도가 무용지물이 됩니다. Circuit Breaker로 일정 횟수 실패 후 자동으로 "오픈 상태"가 되어 일정 시간 외부 시스템 호출 자체를 건너뛰는 패턴이 효율적입니다.

Resilience4j Circuit Breaker + 본 프로젝트 백오프 결합 가능. 본 과제 미도입 이유: 평가 항목 아님 + 백오프만으로도 thundering herd는 막혀서.

### 3.4 메트릭 / 알람

운영 환경엔 다음 메트릭이 필요합니다.

- PENDING / FAILED / DEAD_LETTER 누적 수
- 워커별 처리 속도 (초당 발송 건수)
- Stuck 발생 빈도 — 인프라 사고 지표
- 백오프 시도 횟수 분포 — 외부 시스템 안정성 지표
- 평균/P99 발송 시간

Micrometer + Prometheus + Grafana 통합으로 시각화. 본 과제는 Actuator 기본 헬스체크만.

### 3.5 DEAD_LETTER 수동 재시도 API

도메인 메서드 `Notification.manualRetry()`는 준비되어 있습니다. PENDING으로 되돌리면서 `retry_count`를 0으로 리셋. 운영자가 외부 시스템 복구 확인 후 일괄 재시도하는 시나리오용.

API 엔드포인트만 추가하면 완성. 본 과제는 시간 우선순위로 미구현.

### 3.6 발송 스케줄링

`next_attempt_at` 컬럼이 이미 있어, 특정 시각 발송 예약 기능을 추가하기 쉬운 구조입니다. POST API에 `scheduledAt` 필드 추가 + `Notification.create()` 시 `nextAttemptAt = scheduledAt`로 세팅하면 됩니다. 발송 워커 폴링 쿼리가 이미 `next_attempt_at <= NOW()` 조건이라 자동으로 동작.

선택 구현 항목이지만 시간 관계 상 반영하지 못했습니다.

### 3.7 알림 템플릿 관리 API

현재는 시드 데이터로만 제공. 운영에선 admin 사용자가 템플릿 생성/수정/비활성화하는 API가 필요합니다. CRUD + active 토글 정도면 충분.

선택 구현 항목이라 본 과제 범위에 미포함. `notification_templates` 테이블 구조는 이미 운영 사용 가능한 형태로 만들어져 있어 (active 컬럼, version 컬럼) API만 추가하면 됩니다.


### 3.8 IN_APP 실시간 푸시

현재 IN_APP은 사용자가 `GET /me`를 호출해야 보입니다. WebSocket / SSE 등으로 실시간 푸시 알람으로 고도화할 수 있을 것 같습니다.


### 3.10 발송 중 우선순위 (Priority Queue)

VIP 사용자 알림이나 시간 민감한 알림을 우선 처리하는 기능. 현재는 `next_attempt_at` 기준 FIFO. priority 컬럼 추가 후 `ORDER BY priority, next_attempt_at` 변경하면 됩니다.



---

## 4. 문제가 됐던 부분

개발 중 만난 트러블 중 학습 가치 있는 케이스들을 정리합니다.

### 4.1 @Transactional 자체-호출 함정 (TS-001)

Phase 4 알림 등록 API 구현 시 발생. 같은 클래스 안에서 `@Transactional` 메서드를 호출하면 Spring AOP proxy가 인터셉트 못해 트랜잭션이 적용되지 않는 함정.

이 학습이 Phase 6 워커 구조 설계에 직접 영향을 줬습니다. ProcessingService 안에서 묶음 호출 메서드를 만들지 않고, 외부 클래스인 NotificationProcessor가 두 메서드를 호출하는 구조로 처음부터 설계.

### 4.2 비즈니스 룰을 SQL에 1:1 매핑한 실수 (TS-003)

`GET /me` 처음 구현 시 "수신자 기준 알림 목록"을 단순 `WHERE recipient_id = ?`로 매핑. 사용자 입장에서 PENDING 같은 내부 상태가 노출되는 게 어색하다는 걸 나중에 발견하고 SENT + IN_APP 조건 추가.

도메인 용어가 비즈니스 의미를 함축하고 있는지 의심하는 습관을 들이는 계기였습니다.

### 4.3 시드 데이터와 코드 불일치 (TS-005)

Phase 6 ProcessingService 작성 시 `DEFAULT_LOCALE = "ko"`로 했는데 V2 시드 템플릿은 `locale = 'ko_KR'`. 통합 테스트 작성 직전 시드 데이터를 다시 보다가 발견.

운영 환경에 들어가면 모든 알림이 "Template not found" FAILED 처리될 사고였습니다. 통합 테스트 작성 단계가 코드와 인프라(DB seed) 간 일관성을 잡아주는 가치를 실감.

해결: locale 상수를 'ko_KR'로 맞추고, channel별 전용 → 공통 폴백 패턴을 Optional.or() chaining으로 도입.

### 4.4 @Scheduled 빈 등록 직후 자동 트리거 (TS-006)

Phase 6 워커 통합 테스트가 race condition으로 실패. 분석해보니 `@Scheduled`는 fixedDelay 설정과 무관하게 빈 등록 직후 첫 호출이 즉시 트리거됨. 테스트의 BeforeEach 종료 직후 워커가 PENDING을 가로채는 충돌.

해결: NotificationProcessor에 `@ConditionalOnProperty(matchIfMissing=true)`를 적용해 application-test.yaml에서 명시적 비활성화. 부가 효과로 운영 환경 워커 토글도 가능해짐.

---

## 5. 본 과제에서 의식했던 점

### 시스템 설계 사고

단순 구현보다 **설계 결정의 근거**가 평가의 핵심이라 판단했습니다. 모든 결정마다:

- 선택지를 나열하고
- 각각의 트레이드오프를 비교한 후
- 본 과제 규모와 평가 기준에 맞는 옵션 선택
- 그 결정을 ADR로 기록

면접에서 "왜 이렇게 설계했나요?" 질문에 답할 수 있는 자산이 됩니다.

### 운영 환경 의식

본 과제는 단일 인스턴스로 채점되겠지만, 평가 기준 "다중 인스턴스 환경 중복 처리 방지", "운영 환경 전환 가능한 구조"가 운영 마인드를 본다는 신호로 읽었습니다.

- SKIP LOCKED는 단일/다중 모두 안전
- Dispatcher 인터페이스 추상화로 SMTP 클라이언트 교체 용이
- 설정값 외부화로 운영 튜닝 가능
- ProcessingService / Worker 책임 분리로 Kafka 통합 시 변경 범위 최소화

### 점진적 진행

Phase별로 작게 진행했습니다. 한 Phase 안에서도 결정 단위로 쪼개 ADR 작성 → 코드 → 통합 테스트 → 검증. 큰 단위로 진행하면 부정합을 늦게 발견해서 리팩토링 부담이 큽니다. TS-004 (Dispatcher 시그니처 부정합)가 작은 단위로 진행한 덕에 인터페이스 파일 1개 수정으로 끝난 경험.

---

## 6. AI 활용 정책

본 과제는 Claude Code를 적극 활용해 개발했습니다. 다만:

- **설계 결정은 모두 직접 검토 후 선택**: 옵션 비교, 트레이드오프 분석, 본 과제 규모 적합성을 직접 판단.
- **AI는 구현 보조 + 트러블슈팅 진단 + 문서 정리**: 코드 작성 속도와 학습 자료 정리 효율을 높이는 도구로 활용.
- **모든 코드를 직접 이해**: 면접에서 어느 코드든 직접 설명할 수 있을 정도로 검토.
- **ADR과 학습 노트로 사고 흐름 기록**: 작업 중 모든 결정과 학습 포인트를 별도 문서에 남겨 사고의 일관성 + 면접 자산 확보.

AI를 도구로 활용하되 설계자/결정자는 본인이 되는 워크플로를 의식했습니다.
