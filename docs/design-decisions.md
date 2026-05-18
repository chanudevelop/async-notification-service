# Design Decisions

주요 설계 결정과 근거를 정리합니다. 선택지를 어떻게 좁혔는지, 왜 다른 옵션을 기각했는지, 어떤 트레이드오프를 감수했는지를 짧게 정리하고, 자세한 동작은 다른 문서로 연결합니다.

---

## 1. 상태 머신: 5 states + 도메인 메서드

### 결정
- 상태 5개: `PENDING / PROCESSING / SENT / FAILED / DEAD_LETTER`
- Java 도메인 메서드 + enum으로 구현 (Spring StateMachine 라이브러리 미사용)

### 왜 5개인가
| 상태 | 없으면 발생하는 문제 |
|------|---------------------|
| PROCESSING | 다중 워커 환경에서 같은 알림 중복 처리 |
| FAILED | 일시 실패와 영구 실패 구분 불가 |
| DEAD_LETTER | 무한 재시도 또는 영구 실패 처리 모호 |

### 왜 Spring StateMachine을 안 쓰는가
- 상태 5개 + 단순 전이라 도메인 메서드로 충분히 명확.
- 라이브러리는 대출 심사 같이 상태 수십 개 + 복잡한 가드/액션이 필요한 워크플로용.
- 본 과제 규모에선 오버엔지니어링.

자세한 전이 규칙: [state-machine.md](./state-machine.md)

---

## 2. PK: UUID v4 (대리키)

### 결정
- `id UUID PRIMARY KEY` (Surrogate Key)
- `idempotency_key VARCHAR(255) UNIQUE` (Natural Key, 별도 컬럼)

### 왜 UUID인가 (vs Long)
- 알림 ID가 API 응답에 노출됨 → Long은 IDOR 공격으로 다른 사용자 알림 ID 추측 가능
- 보안 + 분산 환경 확장성 + 마이크로서비스 표준

### 왜 멱등성 키를 PK로 안 쓰는가
**"PK는 절대 변하지 않아야 한다" (Stable PK 원칙)**
- 멱등성 키 정의(필드 조합)가 향후 바뀔 수 있음 → PK 형식 변경 = 외래키 참조 전체 갱신 = 운영 환경에서 불가능
- 자연키는 비즈니스 정보 노출 위험 (recipientId, referenceId 등 URL 노출)
- 모던 RDB 설계 표준: PK = 대리키, UNIQUE 제약 = 자연키 (역할 분리)

---

## 3. 멱등성 키: 시스템 자동 계산 + 4필드 결합

### 결정
- 키 형식: `{recipient_id}:{type}:{reference_id}:{channel}`
- 예: `user-001:ENROLLMENT_COMPLETED:enrollment-123:EMAIL`
- 시스템이 받은 필드로 자동 계산 (클라이언트가 별도 키 제공 X)
- 단일 컬럼 `idempotency_key VARCHAR(255) UNIQUE`

### 왜 4필드 모두 필요한가
| 필드 | 빠지면 발생하는 문제 |
|------|---------------------|
| recipient_id | "누구한테?"가 모호. 멱등성 범위 잘못됨 |
| type | 같은 사용자의 신청완료/취소/D-1 알림이 모두 충돌 |
| reference_id | 사용자가 평생 같은 type 알림을 1번만 받음 (재앙) |
| channel | EMAIL과 IN_APP 동시 발송 시 충돌 |

### 왜 클라이언트 키 (Stripe 방식) 미지원
- 본 시스템은 백엔드 간 B2B 통신, reference_id가 충분히 명확한 식별자
- 본 과제 요구사항에 명시 없음
- 향후 필요시 컬럼 추가로 확장 가능 (YAGNI)

### 동시성 보장
- 같은 키로 동시 100개 요청 → **DB UNIQUE 제약이 1개만 허용**
- 99개는 `DataIntegrityViolationException` → 기존 레코드 반환
- 어플리케이션 레벨 락 불필요

---

## 4. 메시지 페이로드: Hybrid 패턴

### 결정
- `payload (JSONB)`: 변수 데이터 (감사/재렌더용)
- `title, body (TEXT)`: 발송 시점에 렌더된 결과 (조회 빠름)
- `notification_templates` 테이블: type/locale/channel별 템플릿 관리

### 사고 흐름

#### 1단계: 처음엔 "완성된 텍스트 한 컬럼"이 단순해 보였다
하지만 10,000명에게 같은 알림 발송 시 90% 이상 중복.

#### 2단계: payload JSON만 저장하고 발송 시 렌더하는 방식 검토
성능은 SMTP 호출의 1/1000 수준이라 문제 없음.

#### 3단계: EMAIL vs IN_APP 구조적 차이 인식
- EMAIL: "Fire and forget" — 발송 후 메시지 우리 시스템에 없음
- IN_APP: "Store and read" — 사용자가 알림 페이지 반복 조회

#### 4단계: 대용량 트래픽 시 매번 렌더는 비효율
- 활성 사용자 10만 × 일 10회 × 50건 = 일 5천만 회 렌더링
- 각 1ms × 5천만 = 14시간 CPU 시간 매일 낭비

#### 5단계: Hybrid 패턴 채택
- 발송 시점에 한 번 렌더 → title/body 컬럼에 저장
- 조회 시엔 그대로 반환 (렌더링 없음)
- payload는 따로 보관 (디버깅, 감사, 재렌더 가능)

### 부수적 장점
- 템플릿 변경 시 기존 알림은 옛 내용 유지 → 사용자 일관성 + 감사 추적 명확
- EMAIL도 title/body 저장 → "어떤 메시지가 발송됐냐" 운영 문의 시 즉답
- 다국어 / A/B 테스트 / 운영자 admin 수정 등 확장 여지

---

## 5. type / channel: enum + @Enumerated(STRING)

### 결정
- Java enum + `@Enumerated(EnumType.STRING)`
- DB에 enum 이름을 문자열로 저장 (`'ENROLLMENT_COMPLETED'`)
- enum에 displayName, category 같은 메타데이터 포함

### "enum은 거의 변하지 않는 것에만" 원칙 적용
- 닫힌 집합(closed set)은 enum, 열린 집합(open set)은 DB 테이블이 일반 원칙.
- NotificationType은 경계선에 있지만, **새 type 추가가 항상 새 비즈니스 로직 코드를 동반**한다.
- "운영자가 admin에서 type만 추가" 시나리오는 실제 알림 발행 코드 없이는 가짜 유연성.

### ORDINAL 절대 안 쓰는 이유
- `EnumType.ORDINAL`은 enum 선언 순서를 정수로 저장 → 순서 변경 시 기존 데이터 의미 손상
- `EnumType.STRING`은 enum 이름 그대로 → 순서 무관, 안전, 가독성 ↑

---

## 6. 인덱스: Partial Index 활용 (PostgreSQL 특화)

### 결정
3개의 수동 인덱스 (PK / UNIQUE는 자동):

| 이름 | 정의 | 용도 |
|------|------|------|
| `idx_recipient_created` | `(recipient_id, created_at DESC)` | 사용자 알림 목록 |
| `idx_pending_polling` | `(next_attempt_at) WHERE status='PENDING'` | 워커 폴링 (Partial) |
| `idx_stuck_reaper` | `(claimed_at) WHERE status='PROCESSING'` | Stuck Reaper (Partial) |

### 왜 Partial Index인가
- 알림의 99%+가 SENT/DEAD_LETTER로 종착, PENDING/PROCESSING은 1% 미만
- 일반 인덱스는 사용하지 않는 행까지 모두 인덱싱 → 메모리 낭비
- Partial Index는 해당 status인 행만 인덱싱 → **크기 약 1/100, 캐시 효율 ↑, 쿼리 속도 ↑**

### 왜 복합 인덱스의 컬럼 순서가 이런가
**"Equality 컬럼을 leading으로"** 원칙.
- `(recipient_id, created_at DESC)`: recipient_id는 정확 매칭(equality), created_at은 정렬용 → recipient_id가 leading
- Equality 컬럼이 leading이어야 좁은 범위로 한 번에 점프 가능. Range 컬럼이 leading이면 큰 범위 스캔 후 다시 필터링해야 함.

### PostgreSQL 종속에 대한 trade-off
- 다른 DB(MySQL 기본 등)로 이식 시 인덱스 재설계 필요
- 본 프로젝트는 PostgreSQL을 명시적으로 선택했고, 이식 우선순위가 낮으므로 효율을 우선

---

## 7. 비동기 처리: DB 폴링 + SKIP LOCKED + 트랜잭션 분리

### 결정
- 알림은 `notifications` 테이블에 PENDING 상태로 INSERT 후 즉시 `202 Accepted` 응답
- 별도 `NotificationProcessor` 워커가 `@Scheduled`로 1초마다 PENDING 폴링
- 클레임 시 PostgreSQL `SELECT FOR UPDATE SKIP LOCKED` 사용
- 트랜잭션을 두 단계로 분리: 클레임 (`claimPending`) / 발송 (`processOne`)

### 왜 두 단계로 분리했나
한 트랜잭션으로 묶으면 외부 시스템 호출이 트랜잭션 안에 있어 DB 락이 외부 응답 시간만큼 잡힙니다. SMTP가 5초 걸리면 락도 5초 → 다른 워커 처리량 ↓.

분리하면 클레임은 짧게 commit해서 PROCESSING 상태가 즉시 영구화되고, 발송은 별도 트랜잭션에서 외부 시스템 호출. 알림 1건당 별도 트랜잭션이라 한 건 실패가 다른 건에 영향 X.

### Spring AOP self-invocation 회피
두 트랜잭션 메서드를 묶어 호출하는 책임은 `NotificationProcessor` 워커가 가집니다. 같은 클래스 안에서 `@Transactional` 메서드를 호출하면 AOP proxy가 인터셉트 못해 트랜잭션이 적용 안 되는 함정이 있어, 외부 클래스에서 호출하는 구조로 분리.

### SKIP LOCKED 선택 이유
다중 인스턴스 환경에서 동시 폴링 시 락 충돌이 발생합니다. 일반 `FOR UPDATE`는 락 풀릴 때까지 대기 → 처리량 ↓. `SKIP LOCKED`는 충돌 시 그냥 다음 행 보러 가서 처리량 유지. 본 과제 평가 기준 "다중 인스턴스 중복 처리 방지" + "비동기 처리 구조" 둘 다 만족.

### 채널 추상화로 운영 전환 경로 확보
`NotificationDispatcher` 인터페이스 + `EmailDispatcher` / `InAppDispatcher` 구현체 (Strategy + 헥사고날 Port/Adapter). 미래에 SMTP 클라이언트나 메시지 브로커 통합 시 인터페이스는 그대로 유지하고 구현체만 교체.

자세한 비동기 구조는 [async-design.md](./async-design.md) 참조.

---

## 8. 재시도 정책: Exponential Backoff + Jitter

### 결정
- 최대 재시도: 5회 (`DEFAULT_MAX_RETRY = 5`)
- 백오프 공식: `delay = min(base × multiplier^(retryCount-1), max) + random(0, jitter)`
- 파라미터: base=10초, multiplier=2, max=300초, jitter=0~5초
- 한계 초과 시 `DEAD_LETTER` 자동 전이 (운영자 수동 재시도 대상)
- 별도 `RetryProcessor` 워커가 5초마다 FAILED 폴링

### 계산 예시

| retryCount | 백오프 |
|-----------|--------|
| 1 | 10~15초 |
| 2 | 20~25초 |
| 3 | 40~45초 |
| 4 | 80~85초 |
| 5 | 160~165초 |
| 6+ | DEAD_LETTER |

### 왜 Exponential인가
외부 시스템 일시 장애 시 즉시 재시도하면 같은 실패를 반복하며 외부 시스템 회복을 방해합니다 (thundering herd). 매 시도마다 대기를 2배로 늘려 외부 시스템에 충분한 회복 시간을 제공.

### 왜 Jitter가 추가로 필요한가
Exponential만 적용하면 동시에 실패한 N건이 정확히 같은 시각에 재시도됩니다. Jitter는 재시도 시각에 0~5초 랜덤을 더해 분산, 외부 시스템에 부드러운 부하만 가합니다. AWS SDK / Resilience4j 같은 표준 라이브러리와 동일 패턴.

### 워커 분리 이유 (RetryProcessor)
NotificationProcessor가 PENDING+FAILED 둘 다 처리하지 않고 RetryProcessor를 별도 워커로 둔 이유:
- SRP — 발송과 재시도 정책이 다른 책임
- 폴링 주기 다르게 가능 (발송 1초 / 재시도 5초)
- 운영에서 한쪽만 끄기 가능 (`@ConditionalOnProperty`)
- 발송 워커 코드가 비대해지지 않음

자세한 정책은 [async-design.md](./async-design.md#재시도-정책--exponential-backoff--jitter) 참조.

---

## 9. Stuck 복구: 별도 Reaper 워커 + 5분 임계값

### 결정
- 별도 `StuckReaperProcessor` 워커가 1분마다 폴링
- `WHERE status='PROCESSING' AND claimed_at < NOW() - 5분`
- 발견 시 `recoverFromStuck()` 도메인 메서드 호출 → PENDING으로 되돌림
- `retry_count`는 변경 없음 (인프라 사고와 외부 시스템 사고 정책 분리)

### 왜 별도 워커인가
세 워커가 각자 다른 status만 보는 구조 — NotificationProcessor는 PENDING, RetryProcessor는 FAILED, StuckReaperProcessor는 PROCESSING. 어떤 알림도 동시에 두 워커에 잡힐 일이 없습니다. SRP + 운영 시 따로 끄기 가능 + 폴링 주기 분리 가치.

### 임계값 5분의 근거
- 외부 시스템(SMTP, HTTP) 일반 timeout이 30초~2분 범위
- 5분이면 "이건 분명히 죽었다"고 단정 가능한 안전 마진
- 너무 짧으면(예: 30초) 살아있는 워커의 작업을 가로채 중복 발송 사고
- 너무 길면(예: 1시간) 사용자 체감 지연 ↑
- application.yaml에 외부화 (`notification.reaper.stuck-threshold-seconds: 300`) — 운영 P99 측정 후 조정

### retry_count를 안 건드리는 이유
Stuck은 워커 프로세스가 죽은 인프라 사고지 외부 시스템 사고가 아닙니다. retry_count를 증가시키면 인프라가 불안정한 시기에 stuck이 누적되다 DEAD_LETTER로 보내져 외부 시스템 한 번도 거치지 않은 알림이 영구 실패 처리되는 사고가 납니다. 외부 시스템 실패는 markAsFailed에서만 retry_count를 증가시키는 정책 분리.

자세한 동작은 [async-design.md](./async-design.md#stuck-복구) 참조.

---

## 결정 요약 한눈에 보기

| 영역 | 결정 |
|------|------|
| 상태 머신 | 5 states + 도메인 메서드 (Spring StateMachine 미사용) |
| PK | UUID v4 (대리키), idempotency_key는 별도 UNIQUE 컬럼 |
| 멱등성 키 | 시스템 자동 계산, `recipient_id:type:reference_id:channel` |
| 메시지 페이로드 | Hybrid 패턴 — payload + 렌더된 title/body + 템플릿 테이블 |
| type / channel | enum + @Enumerated(STRING) |
| 인덱스 | Partial Index 활용 (PostgreSQL 특화) |
| 비동기 처리 | DB 폴링 + SKIP LOCKED + 트랜잭션 두 단계 분리 + 채널 추상화 |
| 재시도 정책 | Exponential Backoff + Jitter, 최대 5회, DEAD_LETTER 자동 전이 |
| Stuck 복구 | 별도 Reaper 워커, 5분 임계값, retry_count 변경 없음 |
| 워커 책임 분리 | 세 워커가 PENDING / FAILED / PROCESSING 각각 담당 |
| 읽음 처리 | status와 분리된 readAt 컬럼 |
