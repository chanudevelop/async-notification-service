# Design Decisions

본 문서는 알림 발송 시스템 설계의 주요 결정과 그 근거를 정리한다. 평가자가 본 시스템의 사고 흐름을 빠르게 파악할 수 있도록 핵심만 추린다.

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

## 7. 비동기 처리: DB 폴링 기반 (Outbox 풍)

### 결정
- 알림은 `notifications` 테이블에 PENDING 상태로 저장
- 별도 워커가 `@Scheduled`로 주기적 폴링 → 클레임 → 발송
- 메시지 브로커(Kafka 등) 미사용

### 왜 이 구조인가
- **재시작 안전**: 상태가 DB에 영구 저장돼 서버 재시작 후에도 손실 없음
- **트랜잭션 분리**: 알림 등록 API는 INSERT 후 즉시 응답 (비즈니스 트랜잭션 영향 X)
- **다중 인스턴스 안전**: DB의 조건부 UPDATE / `SKIP LOCKED`로 클레임 → 1행 1워커
- **메시지 브로커 없이도 운영 가능**: 본 과제 요구사항 부합

### 운영 환경 전환 경로 (인터페이스 추상화)
- `NotificationDispatcher` 인터페이스로 발송 로직 추상화
- 현재: `DbPollingDispatcher` (구현체)
- 향후 트래픽 증가 시: `KafkaDispatcher` 같은 구현체로 교체 가능 — 비즈니스 코드 영향 없음

자세한 내용은 [async-design.md](./async-design.md) (Phase 12에서 작성 예정) 참조.

---

## 8. 재시도 정책: 지수 백오프 (Exponential Backoff)

### 결정 (잠정 — Phase 7에서 확정)
- 최대 재시도: 5회
- 백오프 간격: 1분 → 5분 → 30분 → 2시간 → 6시간
- 최대 재시도 초과 시 `DEAD_LETTER` 상태로 전이 (운영자 개입 필요)

### 왜 지수 백오프인가
- 외부 SMTP 서버 장애 시 짧은 간격 재시도는 부하만 가중
- 점점 길어지는 간격으로 외부 서버에 회복 시간 부여

자세한 정책은 Phase 7에서 RetryPolicy 클래스 구현 시 확정.

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
| 비동기 처리 | DB 폴링 + 인터페이스 추상화 (브로커 교체 가능) |
| 읽음 처리 | status와 분리된 readAt 컬럼 |
