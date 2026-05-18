# ERD (Entity Relationship Diagram)

알림 발송 시스템의 데이터 모델입니다. 두 개의 테이블 — `notifications`와 `notification_templates`로 구성되어 있고, 둘 사이엔 DB 외래키 없이 논리적 참조 관계만 있습니다 (사유는 아래 설명).

---

## 1. 다이어그램

```mermaid
erDiagram
    NOTIFICATIONS {
        UUID id PK
        VARCHAR idempotency_key UK "recipient_id:type:reference_id:channel 결합"
        VARCHAR recipient_id "수신자 식별자"
        VARCHAR type "NotificationType enum (STRING 저장)"
        VARCHAR channel "EMAIL or IN_APP"
        VARCHAR reference_id "원본 이벤트 식별자 (예: enrollment-123)"
        JSONB payload "템플릿 렌더링용 변수 데이터"
        TEXT title "발송 시점 렌더된 제목"
        TEXT body "발송 시점 렌더된 본문"
        VARCHAR status "NotificationStatus enum"
        INT retry_count "재시도 횟수"
        INT max_retry "최대 재시도 (기본 5)"
        TIMESTAMP next_attempt_at "다음 재시도 예정 시각"
        VARCHAR worker_id "클레임한 워커 ID"
        TIMESTAMP claimed_at "클레임 시각 (Stuck 복구 기준)"
        TEXT last_error "마지막 실패 사유"
        TIMESTAMP failed_at "마지막 실패 시각"
        TIMESTAMP sent_at "발송 완료 시각"
        TIMESTAMP read_at "사용자 읽음 시각 (IN_APP)"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    NOTIFICATION_TEMPLATES {
        UUID id PK
        VARCHAR type "어떤 NotificationType 인가"
        VARCHAR locale "ko_KR / en_US 등"
        VARCHAR channel "EMAIL / IN_APP / null=공통"
        TEXT title_template "변수 자리(예: {{studentName}}) 포함"
        TEXT body_template
        BOOLEAN active "활성/비활성"
        INT version "이력 관리"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    NOTIFICATIONS ||..o| NOTIFICATION_TEMPLATES : "type+channel+locale로 렌더 시 참조 (FK 없음)"
```

> **관계 메모**: `notifications.type`은 `notification_templates.type`을 논리적으로 참조하지만 DB 외래키는 일부러 안 뒀습니다. 템플릿은 운영 중 비활성/삭제될 수 있고, 알림은 발송 시점에 이미 렌더된 결과(title/body)를 보관하므로 템플릿이 사라져도 기존 알림의 무결성엔 영향이 없습니다.

---

## 2. notifications 테이블 상세

### 컬럼 그룹

| 그룹 | 컬럼 | 역할 |
|------|------|------|
| **식별** | `id`, `idempotency_key` | 시스템 식별자 + 비즈니스 식별자 (대리키 + 자연키 분리) |
| **수신자/분류** | `recipient_id`, `type`, `channel`, `reference_id` | 누구한테, 어떤 종류, 어떤 채널, 어떤 이벤트 |
| **메시지** | `payload`, `title`, `body` | 변수 데이터 + 렌더 결과 (Hybrid 패턴) |
| **상태** | `status`, `retry_count`, `max_retry`, `next_attempt_at` | 생애주기 + 재시도 정책 |
| **클레임/복구** | `worker_id`, `claimed_at` | 다중 워커 환경 + Stuck 복구 |
| **실패 추적** | `last_error`, `failed_at` | 디버깅 |
| **처리 시각** | `sent_at`, `read_at` | 발송 시각, 사용자 읽음 시각 |
| **감사** | `created_at`, `updated_at` | 자동 관리 |

### 핵심 제약

- `id` UUID PRIMARY KEY (자동 인덱스)
- `idempotency_key` UNIQUE NOT NULL (자동 인덱스, 멱등성 보장)
- `status`, `type`, `channel` NOT NULL (필수)

### 인덱스

| 이름 | 정의 | 용도 |
|------|------|------|
| (자동) PK | `(id)` | 단일 행 조회 |
| (자동) UNIQUE | `(idempotency_key)` | 멱등성 체크 |
| `idx_recipient_created` | `(recipient_id, created_at DESC)` | 사용자 알림 목록 |
| `idx_pending_polling` | `(next_attempt_at) WHERE status='PENDING'` | 워커 폴링 (Partial) |
| `idx_stuck_reaper` | `(claimed_at) WHERE status='PROCESSING'` | Stuck Reaper (Partial) |

알림의 99% 이상이 SENT/DEAD_LETTER 종착 상태로 끝납니다. 폴링/Reaper 쿼리가 항상 특정 status로 시작하니까 그 status 행만 인덱싱하는 Partial Index가 적합합니다. 인덱스 크기가 약 1/100로 줄어들어 메모리 캐시 효율 + 쿼리 속도 둘 다 잡힙니다. 자세한 이유는 [design-decisions.md](./design-decisions.md) 참조.

---

## 3. notification_templates 테이블 상세

알림 type / locale / channel별 메시지 템플릿. 변수는 `{{name}}` 형식으로 표기 (Mustache 스타일).

### 핵심 제약

- `id` UUID PRIMARY KEY
- `UNIQUE (type, locale, channel)` — 같은 (타입, 언어, 채널) 조합엔 활성 템플릿 1개

### 시드 데이터 예시

| type | locale | channel | title_template | body_template |
|------|--------|---------|-----------------|---------------|
| `ENROLLMENT_COMPLETED` | `ko_KR` | null (공통) | 수강 신청이 완료되었습니다 | {{studentName}}님, {{courseName}} 강의 신청이 완료되었습니다. |
| `PAYMENT_CONFIRMED` | `ko_KR` | null | 결제가 완료되었습니다 | {{amount}}원 결제가 완료되었습니다. |
| `CLASS_STARTS_TOMORROW` | `ko_KR` | null | 내일 강의가 시작됩니다 | {{courseName}} 강의가 내일 시작됩니다. |
| `ENROLLMENT_CANCELLED` | `ko_KR` | null | 수강 신청이 취소되었습니다 | {{courseName}} 수강 신청이 취소되었습니다. |

→ Phase 3의 Flyway `V2__notification_templates.sql`에서 INSERT.

---

## 4. 데이터 흐름 (요약)

### 등록 + 정상 발송

```
[API: POST /notifications]
   ↓ 멱등성 키 생성 (recipient_id:type:reference_id:channel)
[INSERT (status=PENDING, payload, ...)]
   - UNIQUE 충돌 시 기존 행 반환
   ↓
[NotificationProcessor: idx_pending_polling으로 PENDING 폴링]
   ↓ SKIP LOCKED로 클레임 (status=PROCESSING, worker_id, claimed_at)
[템플릿 조회 + payload 치환 → 렌더된 title/body]
   ↓ Dispatcher 호출 (EMAIL은 SMTP, IN_APP은 로그)
[성공: status=SENT, sent_at + title/body 저장]
```

### 실패 → 재시도 → DEAD_LETTER

```
[발송 실패: markAsFailed]
   → status=FAILED, retry_count++, last_error, failed_at

[RetryProcessor 5초 폴링]
   ↓ FAILED + retry_count < max_retry ?
       ├─ YES: 백오프 시각 계산 → scheduleRetry
       │       → status=PENDING, next_attempt_at=미래시각
       │       → 시간 경과 후 NotificationProcessor가 다시 폴링
       │
       └─ NO:  moveToDeadLetter → status=DEAD_LETTER (자동 처리 종료)
```

### Stuck 복구

```
[워커 죽음] PROCESSING 상태로 갇힘

[StuckReaperProcessor 1분 폴링]
   ↓ idx_stuck_reaper로 PROCESSING + claimed_at < (NOW - 5분) 조회
[recoverFromStuck: status=PENDING, worker_id/claimed_at null]
   ↓
[NotificationProcessor가 다시 폴링해서 잡음]
```

### 사용자 알림 조회

```
[GET /notifications/me]
   ↓ idx_recipient_created로 빠른 조회
[저장된 title/body 그대로 응답 — 렌더링 없음]
```

조회 시 렌더링을 다시 안 하는 이유는 발송 시점에 렌더된 결과를 `notifications.title/body`에 저장해 두었기 때문입니다 (Hybrid 패턴). 템플릿이 운영 중 변경되더라도 사용자가 받았던 알림은 발송 시점 그대로 유지됩니다.

---

## 5. 설계 결정 요약

- **PK**: UUID v4 (대리키)
- **idempotency_key**: 자연키, UNIQUE 제약으로 멱등성 보장
- **메시지**: Hybrid 패턴 (payload + 렌더된 title/body 둘 다 저장)
- **상태 머신**: PENDING / PROCESSING / SENT / FAILED / DEAD_LETTER (5 states)
- **인덱스**: 자주 쓰는 쿼리 3개에 Partial Index 활용

자세한 결정 배경은 [design-decisions.md](./design-decisions.md), 상태 머신은 [state-machine.md](./state-machine.md) 참조.
