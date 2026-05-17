-- =====================================================================
-- V1: notifications 테이블 + 인덱스
-- =====================================================================
-- ADR-005, ADR-006 결정 반영:
-- - PK: UUID (대리키)
-- - idempotency_key: 자연키, UNIQUE 제약 (멱등성 보장)
-- - 상태: PENDING / PROCESSING / SENT / FAILED / DEAD_LETTER
-- - 메시지: payload(JSONB) + 렌더된 title/body (Hybrid 패턴)
-- - 인덱스: Partial Index 활용 (PostgreSQL 특화)
-- =====================================================================

CREATE TABLE notifications (
    id                  UUID            PRIMARY KEY,

    -- 멱등성 키 (자연키)
    idempotency_key     VARCHAR(255)    NOT NULL,

    -- 비즈니스 분류
    recipient_id        VARCHAR(64)     NOT NULL,
    type                VARCHAR(50)     NOT NULL,
    channel             VARCHAR(20)     NOT NULL,
    reference_id        VARCHAR(64),

    -- 메시지 (Hybrid 패턴: 변수 + 렌더 결과)
    payload             JSONB,
    title               TEXT,
    body                TEXT,

    -- 상태 / 재시도
    status              VARCHAR(32)     NOT NULL,
    retry_count         INT             NOT NULL DEFAULT 0,
    max_retry           INT             NOT NULL DEFAULT 5,
    next_attempt_at     TIMESTAMP,

    -- 클레임 / Stuck 복구
    worker_id           VARCHAR(64),
    claimed_at          TIMESTAMP,

    -- 실패 추적
    last_error          TEXT,
    failed_at           TIMESTAMP,

    -- 처리 시각
    sent_at             TIMESTAMP,
    read_at             TIMESTAMP,

    -- 감사 (BaseEntity)
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP       NOT NULL,

    CONSTRAINT uk_notifications_idempotency_key UNIQUE (idempotency_key)
);

-- =====================================================================
-- 인덱스 (자동 PK/UNIQUE 외 3개)
-- =====================================================================

-- 사용자 알림 목록 조회용 (복합 인덱스, equality + range 패턴)
CREATE INDEX idx_recipient_created
    ON notifications (recipient_id, created_at DESC);

-- 워커 폴링용 (Partial Index — status='PENDING'인 행만 인덱싱)
CREATE INDEX idx_pending_polling
    ON notifications (next_attempt_at)
    WHERE status = 'PENDING';

-- Stuck Reaper용 (Partial Index — status='PROCESSING'인 행만 인덱싱)
CREATE INDEX idx_stuck_reaper
    ON notifications (claimed_at)
    WHERE status = 'PROCESSING';

-- =====================================================================
-- 컬럼 코멘트 (DataGrip / pg_dump에서 가독성 ↑)
-- =====================================================================

COMMENT ON TABLE notifications IS '알림 본체 — 발송 라이프사이클 추적';
COMMENT ON COLUMN notifications.idempotency_key IS '멱등성 키: recipient_id:type:reference_id:channel';
COMMENT ON COLUMN notifications.status IS 'PENDING | PROCESSING | SENT | FAILED | DEAD_LETTER';
COMMENT ON COLUMN notifications.payload IS '템플릿 렌더링용 변수 (JSONB)';
COMMENT ON COLUMN notifications.title IS '발송 시점에 렌더된 제목';
COMMENT ON COLUMN notifications.body IS '발송 시점에 렌더된 본문';
COMMENT ON COLUMN notifications.read_at IS '사용자 읽음 시각 (IN_APP, null이면 안 읽음)';
