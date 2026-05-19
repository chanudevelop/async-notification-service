# Async Notification Service

프로덕트 엔지니어 과제 (BE-C). 비동기 알림 발송 시스템입니다.

알림 등록은 REST API로 받고, 발송은 `@Scheduled` 워커가 백그라운드에서 처리합니다. PostgreSQL의 `SELECT FOR UPDATE SKIP LOCKED`로 다중 인스턴스 환경에서도 같은 알림을 두 번 처리하지 않도록 했고, 실패하면 Exponential Backoff + Jitter로 재시도, 한계 초과 시 DEAD_LETTER로 격리합니다. 워커가 죽어서 갇힌 알림(stuck)은 별도 Reaper가 1분마다 발견해 복구합니다.

---

## 개요

| 과제 요구사항 | 구현 위치 |
|-------------|---------|
| 알림 발송 요청 API | `POST /notifications`  |
| 알림 상태 조회 | `GET /notifications/{id}`  |
| 사용자 알림 목록 (읽음 필터) | `GET /notifications/me`  |
| 중복 발송 방지 (동일 이벤트 / 동시 요청) | 멱등성 키 + UNIQUE 제약  |
| 비동기 처리 구조 | `NotificationProcessor` (`@Scheduled`) |
| 재시도 정책 | `RetryProcessor` + Exponential Backoff |
| 처리 중 상태 복구 | `StuckReaperProcessor` |
| 다중 인스턴스 중복 처리 방지 | `SELECT FOR UPDATE SKIP LOCKED` |
| 재시작 후 유실 없음 | 모든 상태 DB 저장 |

---

## 기술 스택

- **Java 21** — Virtual Threads 가능성 + LTS 수명
- **Spring Boot 4.0.6** / Spring 7.0.7
- **PostgreSQL 16** — `SKIP LOCKED`, Partial Index, JSONB
- **Flyway** — 마이그레이션 (V1 notifications, V2 templates)
- **Testcontainers** — 통합 테스트용 실제 PostgreSQL 컨테이너
- **Lombok** — 보일러플레이트 축소
- **Gradle** (Groovy DSL)

PostgreSQL을 고른 이유는 두 가지입니다. 워커 폴링 핵심 기능인 `SELECT FOR UPDATE SKIP LOCKED`를 자연스럽게 지원하고, Partial Index로 `WHERE status='PENDING'` 같은 조건에 인덱스 크기를 100배 가깝게 줄일 수 있어서입니다. 알림처럼 SENT가 99% 차지하는 데이터에서 효율적이라고 판단했습니다.

---

## 실행 방법

### 사전 요구
- Java 21
- Docker (PostgreSQL 컨테이너용)
- Gradle 9.x는 wrapper 포함이라 별도 설치 X

### 1. 클론
```bash
git clone https://github.com/chanudevelop/async-notification-service.git
cd async-notification-service
```

### 2. PostgreSQL 띄우기
```bash
docker compose up -d
```

### 3. 애플리케이션 실행
```bash
./gradlew bootRun
```

기본 프로필 `local`로 뜹니다. `localhost:8080`에서 동작.

### 4. 알림 등록 + 1초 안에 발송 확인
```bash
curl -X POST http://localhost:8080/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "recipientId": "user-001",
    "type": "ENROLLMENT_COMPLETED",
    "channel": "IN_APP",
    "referenceId": "enrollment-123",
    "payload": {
      "studentName": "홍길동",
      "courseName": "Spring Boot 실전"
    }
  }'
```

응답:
```json
{
  "id": "...",
  "status": "PENDING",
  "isDuplicate": false,
  "idempotencyKey": "user-001:ENROLLMENT_COMPLETED:enrollment-123:IN_APP"
}
```

1초 안에 콘솔에 다음 로그가 찍힙니다:
```
[CLAIM] workerId=worker-xxx, claimed=1
[IN_APP] delivered to user-001 (... title=수강 신청이 완료되었습니다)
[PROCESS] id=... → SENT
```

DB(`notifications` 테이블)에서 해당 알림 status가 `SENT`로 바뀌고 title/body가 채워진 걸 확인할 수 있습니다.

### 5. 사용자 알림 목록 조회
```bash
curl -H "X-User-Id: user-001" http://localhost:8080/notifications/me
```

### 6. Swagger UI
브라우저에서 `http://localhost:8080/swagger-ui.html` — 전체 API 시연 + 스키마.

### 7. Actuator 헬스체크
```bash
curl http://localhost:8080/actuator/health
```

### 8. 테스트 실행
```bash
./gradlew test
```

Testcontainers가 PostgreSQL 16 컨테이너를 자동으로 띄워 통합 테스트 45개를 돌립니다.

---

## 요구사항 해석 및 가정

과제 요구사항을 읽으면서 모호하거나 가정이 필요한 부분이 몇 군데 있었습니다. 어떻게 해석했는지 정리합니다.

### "수신자 기준 알림 목록 (읽음/안읽음 필터)"

처음엔 단순히 `WHERE recipient_id = ?`로 구현했는데, 통합 테스트 작성 중 사용자 입장에서 검토해보니 PENDING이나 FAILED 같은 내부 상태 알림까지 노출하면 어색했습니다. "받은 알림"이라는 도메인 용어가 실제로는 **SENT + IN_APP**을 함축하고 있다고 해석했습니다. 근거는 두 가지:

- 같은 요구사항에 "읽음/안읽음 필터"가 있는데, EMAIL은 읽음 추적이 불가능하니 이 필터는 본질적으로 IN_APP을 전제로 합니다.
- PENDING 상태 알림이 사용자 알림함에 보이면 "신청 완료 알림이 왔다는데 아직 안 도착했네?" 같은 혼란이 생깁니다.

→ Repository 쿼리에 `status='SENT' AND channel='IN_APP'`을 명시했습니다.

### "처리 중 상태가 일정 시간 이상 지속되는 경우 복구"

"일정 시간"을 5분으로 잡았습니다. 외부 시스템(SMTP, HTTP) timeout이 보통 30초~2분 범위라 5분이면 정상 흐름과 비정상 흐름이 명확히 구분됩니다. 너무 짧으면 살아 있는 워커의 작업을 가로채 중복 발송 사고가 나고, 너무 길면 사용자 체감 지연이 커집니다.

운영에선 정상 발송 P99를 측정해서 그 2~3배로 조정해야 하는 값이라 `application.yaml`에 외부화했습니다 (`notification.reaper.stuck-threshold-seconds: 300`).

### "다중 인스턴스 환경에서 중복 처리 X"

운영에서는 같은 jar 파일을 여러 서버에서 실행하는 게 표준이라 가정했습니다.
세 워커 모두 `SELECT FOR UPDATE SKIP LOCKED` 패턴을 적용했습니다. 락 충돌 시 대기하지 않고 건너뛰기 때문에 처리량 손실도 없습니다.

### "발송 실패 시 재시도"

재시도 간격을 어떻게 둘지는 명시 되어있지 않아, 즉시 재시도하면 외부 시스템 장애 시 thundering herd 사고가 나서 Exponential Backoff + Jitter를 적용했습니다.

- 첫 재시도: 10초 후
- 매 시도 2배 증가, 최대 5분 상한
- 동시 실패 알림이 같은 시각에 몰리지 않도록 0~5초 jitter 추가

5회까지 실패하면 DEAD_LETTER로 격리 (자동 발송 대상 제외).

### 가정한 항목들

- **locale**: 본 과제는 한국어 단일 가정. 시드 템플릿은 `ko_KR`만. 운영 다국어 지원 시 사용자 정보에서 locale 받아오는 방식으로 확장 예정.
- **인증**: 본격 인증 시스템 대신 `X-User-Id` 헤더로 간략화. 실제 운영은 JWT/SecurityContext를 통해야 합니다.
- **템플릿 관리 API**: 선택 구현 항목이라 본 과제 범위 밖. 시드 데이터만 V2 마이그레이션에 포함.

---

## 설계 결정 요약

자세한 결정 기록은 [docs/design-decisions.md](docs/design-decisions.md), 상태 머신은 [docs/state-machine.md](docs/state-machine.md), 데이터 모델은 [docs/erd.md](docs/erd.md), 비동기 처리 + 재시도 정책은 [docs/async-design.md](docs/async-design.md)에 있습니다. 여기서는 핵심만 짚습니다.

### 1. 멱등성 (중복 발송 방지)

`(recipientId, type, referenceId, channel)` 조합으로 멱등성 키를 만들고 DB UNIQUE 제약을 걸었습니다. Optimistic INSERT 패턴 — 일단 INSERT 시도하고 UNIQUE 위반 예외 잡으면 기존 알림 ID를 반환합니다. SELECT 후 INSERT 패턴은 두 요청 사이 race condition이 있어서 채택하지 않았습니다.

### 2. 비동기 워커 + 동시성

`@Scheduled` 기반 `NotificationProcessor`가 1초마다 PENDING 알림을 폴링합니다. 핵심은 PostgreSQL의 `SELECT FOR UPDATE SKIP LOCKED` — 다른 워커가 잡은 행은 대기 없이 건너뛰기. 폴링 후 도메인 메서드 `startProcessing(workerId)`로 PROCESSING 전이.

트랜잭션은 두 단계로 분리했습니다.

- 1단계 (`claimPending`): 폴링 + PROCESSING 전이. DB 작업만이라 매우 짧음.
- 2단계 (`processOne`): 템플릿 렌더 + Dispatcher 호출 + SENT/FAILED 저장. 외부 시스템 호출이 포함되어 길어질 수 있음.

한 트랜잭션에 묶으면 외부 시스템 응답 시간만큼 DB 락이 잡혀 다른 워커 처리량을 떨어뜨립니다. 분리하면 락은 빨리 풀리고 발송은 여유 있게 처리됩니다. 묶음 호출은 ProcessingService 안이 아닌 워커 클래스에서 — Spring AOP proxy의 self-invocation 함정 회피.

### 3. 채널 추상화

Dispatcher 인터페이스 + EmailDispatcher / InAppDispatcher 구현체. Strategy + 헥사고날 Port/Adapter 패턴입니다. 인터페이스는 도메인 패키지에, 구현체는 인프라 패키지에 둬서 도메인이 외부 시스템에 의존하지 않습니다.

새 채널(PUSH 등) 추가 시 Dispatcher 구현체에 `@Component`만 붙이면 됩니다. Spring이 `List<NotificationDispatcher>`로 자동 주입하고, ProcessingService 생성자에서 `Map<Channel, Dispatcher>`로 캐싱해서 O(1) 조회합니다. OCP 보장.

### 4. 템플릿 (Hybrid 패턴)

`payload`(JSONB) + `notification_templates` 테이블을 합쳐 렌더링하는 Hybrid 패턴입니다. payload에는 동적 변수만, 템플릿은 별도 테이블로 관리.

```
payload = {"studentName": "홍길동", "courseName": "Spring"}
template.titleTemplate = "{{studentName}}님, 수강 신청이 완료되었습니다"
template.bodyTemplate  = "{{studentName}}님, {{courseName}} 강의 신청이 완료되었습니다."

→ rendered.title = "홍길동님, 수강 신청이 완료되었습니다"
→ rendered.body  = "홍길동님, Spring 강의 신청이 완료되었습니다."
```

치환 후에도 `{{...}}` 패턴이 남으면 (= payload에 변수 누락) 즉시 예외 → markAsFailed. 잘못된 데이터를 조용히 발송하느니 명시적 실패가 안전합니다 (Fail Fast).

### 5. 재시도 + 백오프 

별도 `RetryProcessor`가 5초마다 FAILED 알림을 폴링하고, Exponential Backoff + Jitter로 다음 시도 시각을 계산해 `scheduleRetry`로 PENDING으로 되돌립니다. 5회 한계 초과 시 `moveToDeadLetter`로 영구 격리.

```
delay = min(base × multiplier^(retryCount-1), max) + random(0, jitter)
base=10s, multiplier=2, max=300s, jitter=5s

retryCount: 1 → 10~15초
            2 → 20~25초
            3 → 40~45초
            4 → 80~85초
            5 → 160~165초
            6+ → DEAD_LETTER
```

Jitter는 동시에 실패한 다수 알림이 같은 시각에 재시도되어 외부 시스템에 다시 폭주하는 thundering herd 문제를 분산합니다.

### 6. Stuck 복구 

워커 프로세스가 PENDING을 PROCESSING으로 잡은 직후 죽으면 그 알림이 영원히 갇힙니다. `StuckReaperProcessor`가 1분마다 `claimed_at`이 5분 이상 지난 PROCESSING을 발견해 `recoverFromStuck`으로 PENDING 복구. 다음 발송 워커가 다시 잡아서 재처리.

복구 시 `retry_count`는 건드리지 않습니다. Stuck은 인프라 사고지 외부 시스템 사고가 아니라, retry_count 증가시키면 인프라가 불안정한 시기에 stuck이 누적되다 DEAD_LETTER로 보내져 외부 시스템 한 번도 거치지 않은 알림이 영구 실패 처리되는 사고가 나기 때문입니다.

### 7. 동시 읽음 처리(선택구현)
읽음 API를 여러 기기에서 동시에 읽음 처리 요청이 와도, 조건부 `UPDATE notifications SET read_at = NOW() WHERE id = ? AND read_at IS NULL`
 를 사용하여 DB가 같은 row에 대해 lock을 잡고 원자적으로 처리하여 먼저 처리된 요청만 read_at을 세팅해 1행이 갱신되고, 이후 요청은 조건이 맞지 않아 0행 갱신되도록 설계하였습니다.

### 세 워커가 어떻게 충돌 안 하나

| 워커 | 보는 상태 | 폴링 주기 |
|------|---------|---------|
| NotificationProcessor | PENDING | 1초 |
| RetryProcessor | FAILED | 5초 |
| StuckReaperProcessor | PROCESSING (5분 이상 갇힌 것) | 1분 |

각자 다른 상태만 봐서 어떤 알림도 동시에 두 워커에 잡힐 일이 없습니다. "한 상태 = 한 워커" 원칙.

---

## API

전체는 Swagger UI(`/swagger-ui.html`)에서 확인할 수 있습니다. 핵심만 정리:

### `POST /notifications` — 알림 등록 (비동기)

```bash
curl -X POST http://localhost:8080/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "recipientId": "user-001",
    "type": "ENROLLMENT_COMPLETED",
    "channel": "IN_APP",
    "referenceId": "enrollment-123",
    "payload": {"studentName": "홍길동", "courseName": "Spring"}
  }'
```

**202 Accepted** — 비동기라 즉시 응답.

같은 멱등성 키로 두 번 등록하면 `isDuplicate: true` + 기존 알림 ID 반환.

### `GET /notifications/{id}` — 알림 상세

발송 상태와 메타데이터 확인용. status, retry_count, sent_at 등.

### `GET /notifications/me?read=false&page=0&size=20` — 사용자 알림 목록

`X-User-Id` 헤더 필수. SENT + IN_APP만 노출. `read` 파라미터로 읽음/안읽음 필터.

```bash
curl -H "X-User-Id: user-001" "http://localhost:8080/notifications/me?read=false"
```

### `PATCH /notifications/{id}/read` — 알림 읽음 처리

`X-User-Id` 헤더 필수. 본인 알림만 처리 가능 (다른 사용자 알림에 호출 시 403).

```bash
curl -X PATCH -H "X-User-Id: user-001" http://localhost:8080/notifications/{id}/read
```

응답:
```json
{
  "id": "...",
  "firstRead": true
}
```

- `firstRead: true` — 이번 호출이 첫 읽음 처리 (read_at 세팅됨)
- `firstRead: false` — 이미 읽음 상태였음 (멱등성)

여러 기기에서 동시에 호출돼도 DB 조건부 UPDATE (`WHERE read_at IS NULL`)로 정확히 첫 호출만 read_at 세팅. 자세한 설계는 [docs/design-decisions.md](docs/design-decisions.md) 참조.

### 응답 에러 형식

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "path": "/notifications",
  "timestamp": "2026-05-18T10:00:00",
  "errors": [
    {"field": "recipientId", "message": "must not be blank"}
  ]
}
```

`@RestControllerAdvice` 기반 GlobalExceptionHandler. REST 표준 응답 형식.

---

## 데이터 모델

### notifications 테이블

핵심 컬럼만:
- `id` (UUID, PK)
- `idempotency_key` (UNIQUE) — `recipient:type:reference:channel`
- `status` — PENDING / PROCESSING / SENT / FAILED / DEAD_LETTER
- `payload` (JSONB) — 템플릿 변수
- `title`, `body` (TEXT) — 발송 시 렌더된 결과 저장
- `retry_count`, `max_retry`, `next_attempt_at` — 재시도 추적
- `worker_id`, `claimed_at` — 클레임 / Stuck 판정
- `last_error`, `failed_at` — 실패 추적
- `sent_at`, `read_at` — 발송 / 읽음 시각

### 인덱스 — Partial Index 활용

```sql
-- 사용자 알림 목록용 (일반 복합 인덱스)
CREATE INDEX idx_recipient_created
    ON notifications (recipient_id, created_at DESC);

-- 워커 폴링용 (Partial: PENDING만)
CREATE INDEX idx_pending_polling
    ON notifications (next_attempt_at)
    WHERE status = 'PENDING';

-- Stuck Reaper용 (Partial: PROCESSING만)
CREATE INDEX idx_stuck_reaper
    ON notifications (claimed_at)
    WHERE status = 'PROCESSING';
```

알림은 SENT/DEAD_LETTER 종착 상태로 끝납니다. Partial Index로 PENDING/PROCESSING만 인덱싱하면 인덱스 크기가 100배 가깝게 줄어들어 메모리 캐시 효율 + 쿼리 속도가 크게 좋아집니다.

자세한 ERD: [docs/erd.md](docs/erd.md)

---

## 테스트

```bash
./gradlew test
```

통합 테스트 **45개** 모두 통과:
- Application bootstrap (1)
- 알림 등록 API + 멱등성 (11)
- 조회 API + 비즈니스 룰 (14)
- 발송 워커 (6)
- 재시도 + DEAD_LETTER (5)
- Stuck Reaper (5)
- **동시성 자동 검증** (3) — 멱등성 100 동시 요청, SKIP LOCKED 다중 워커 클레임, 읽음 멱등성

모든 통합 테스트는 Testcontainers로 실제 PostgreSQL 16 컨테이너에서 실행됩니다. H2 in-memory 대신 실제 DB를 쓰는 이유는 Partial Index, `SKIP LOCKED`, JSONB 같은 PostgreSQL 특화 기능이 운영과 동일하게 동작하는지 검증해야 하기 때문입니다.

동시성 자동 테스트(`ConcurrencyIntegrationTest`)는 `ExecutorService` + `CountDownLatch` 패턴으로 같은 멱등성 키를 100개 스레드가 동시에 요청해도 DB UNIQUE 제약 + Optimistic INSERT가 정확히 1건만 통과시키는지, 두 워커가 동시에 `claimPending`을 호출해도 SKIP LOCKED로 같은 알림을 두 번 잡지 않는지를 검증합니다.

---

## 미구현 / 제약사항

### 미구현
- **선택 구현 항목(1) - 발송 스케줄링**: 특정 시각에 발송 예약 기능
- **선택 구현 항목(3) - 최종 실패 알림 보관 및 수동 재시도**

### 일부 구현
- **선택 구현 항목(2) - 알림 템플릿 관리 (타입별 메시지 템플릿)**: `notification_templates` 테이블로 타입별 메시지 템플릿을 관리하고 사용하고 있습니다. 다만 운영자가 템플릿을 관리하는 API(CRUD)는 시간 관계상 구현하지 못해 일부 구현으로 분류했습니다.

### 의도적 단순화

- **인증** — JWT/SecurityContext 대신 `X-User-Id` 헤더로 간략화. 실제 운영은 인증 미들웨어 필요.
- **외부 발송** — SMTP / FCM / APNs는 Mock(콘솔 로그)으로 대체. Dispatcher 인터페이스 그대로 실제 클라이언트로 교체 가능한 구조.
- **모니터링/메트릭** — Actuator 기본 헬스체크만. 

### 운영 환경 전환 시 변경 포인트

자세한 내용은 [docs/async-design.md](docs/async-design.md)에 정리했습니다. 핵심:
- Dispatcher Mock → 실제 SMTP/FCM 클라이언트 교체
- `notification.*` 설정값 운영 트래픽 기준 튜닝
- Multi-instance 환경에서 `@Scheduled` 중복 실행 방지 (필요 시 ShedLock 등 distributed lock 도입)

---

## AI 활용 범위

본 과제는 Claude Code를 적극 활용해서 개발했습니다. 다만 모든 설계 결정은 직접 검토하고 선택했으며, 설계 결정의 트레이드오프와 근거를 이해하며 진행했습니다.

- **Claude 활용**: 코드 구현, 통합 테스트 작성(단일 스레드 + 동시성 자동 테스트 포함), 트러블슈팅 진단, ADR/학습 노트 정리, 외부 문서(README, docs/) 초안 작성
- **직접 결정**: 모든 설계 옵션 선택 (멱등성 방식, 워커 분리, 백오프 전략, 임계값 등)
- **개발 흐름**: 옵션과 트레이드오프를 함께 검토 → 직접 선택 → ADR로 기록 → 코드 구현 → 통합 테스트 검증
- **동시성 자동 테스트** (`ConcurrencyIntegrationTest`): `ExecutorService` + `CountDownLatch` 패턴으로 멱등성·SKIP LOCKED 검증 시나리오 코드 작성

---

## 프로젝트 구조

```
src/main/java/com/chanudevelop/notification/
├── application/
│   ├── NotificationService.java              # REST API용
│   ├── NotificationProcessingService.java    # 한 알림 발송 처리 (Phase 6)
│   ├── RetryService.java                     # 재시도 / DEAD_LETTER 전이 (Phase 7)
│   ├── ReaperService.java                    # Stuck 복구 (Phase 8)
│   └── worker/
│       ├── NotificationProcessor.java        # @Scheduled 발송 워커
│       ├── RetryProcessor.java               # @Scheduled 재시도 워커
│       └── StuckReaperProcessor.java         # @Scheduled Stuck Reaper
├── domain/
│   ├── Notification.java                     # Rich Domain Model
│   ├── NotificationTemplate.java
│   ├── NotificationStatus.java               # 상태 enum
│   ├── NotificationChannel.java              # EMAIL, IN_APP
│   ├── NotificationType.java
│   ├── BaseEntity.java                       # JPA Auditing
│   ├── dispatcher/                           # 채널 추상화 (Port)
│   ├── template/                             # TemplateRenderer
│   ├── retry/                                # BackoffCalculator
│   └── exception/                            # 도메인 예외
├── infrastructure/
│   ├── dispatcher/                           # EmailDispatcher, InAppDispatcher (Adapter)
│   └── persistence/                          # JpaRepository
└── web/
    ├── controller/
    ├── dto/
    └── exception/                            # GlobalExceptionHandler
```

---

## 문서

- [docs/erd.md](docs/erd.md) — 데이터 모델 + 인덱스 설계
- [docs/state-machine.md](docs/state-machine.md) — 상태 머신 다이어그램 + 전이 규칙
- [docs/design-decisions.md](docs/design-decisions.md) — 주요 설계 결정 정제본
- [docs/async-design.md](docs/async-design.md) — 비동기 처리 구조 + 재시도 정책 (과제 추가 제출물)
- [docs/requirements-interpretation.md](docs/requirements-interpretation.md) — 요구사항 해석 + 개선 의견 (과제 추가 제출물)
