-- =====================================================================
-- V2: notification_templates 테이블 + 시드 데이터
-- =====================================================================
-- ADR-006 Sub-Decision 3 결정 반영 (Hybrid 패턴):
-- - 템플릿을 DB로 관리해 운영자 수정 가능 / 다국어 / 버전 관리 확장 여지
-- - (type, locale, channel) UNIQUE 제약
--   * channel=NULL은 "모든 채널 공통" 의미
--   * NULLS NOT DISTINCT로 NULL도 동등하게 취급 (PostgreSQL 15+)
-- =====================================================================

CREATE TABLE notification_templates (
    id              UUID            PRIMARY KEY,
    type            VARCHAR(50)     NOT NULL,
    locale          VARCHAR(10)     NOT NULL DEFAULT 'ko_KR',
    channel         VARCHAR(20),

    title_template  TEXT            NOT NULL,
    body_template   TEXT            NOT NULL,

    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    version         INT             NOT NULL DEFAULT 1,

    -- 감사 (BaseEntity)
    created_at      TIMESTAMP       NOT NULL,
    updated_at      TIMESTAMP       NOT NULL,

    CONSTRAINT uk_template_type_locale_channel
        UNIQUE NULLS NOT DISTINCT (type, locale, channel)
);

COMMENT ON TABLE notification_templates IS '알림 type/locale/channel별 메시지 템플릿';
COMMENT ON COLUMN notification_templates.channel IS 'NULL = 모든 채널 공통';
COMMENT ON COLUMN notification_templates.title_template IS 'Mustache 스타일 변수 자리 (예: {{studentName}})';

-- =====================================================================
-- 시드 데이터: 4개 type의 기본 ko_KR 템플릿 (channel=NULL, 공통)
-- =====================================================================

INSERT INTO notification_templates (
    id, type, locale, channel, title_template, body_template, active, version, created_at, updated_at
) VALUES
    (gen_random_uuid(),
     'ENROLLMENT_COMPLETED', 'ko_KR', NULL,
     '수강 신청이 완료되었습니다',
     '{{studentName}}님, {{courseName}} 강의 신청이 완료되었습니다.',
     TRUE, 1, NOW(), NOW()),

    (gen_random_uuid(),
     'ENROLLMENT_CANCELLED', 'ko_KR', NULL,
     '수강 신청이 취소되었습니다',
     '{{studentName}}님, {{courseName}} 강의 신청이 취소되었습니다.',
     TRUE, 1, NOW(), NOW()),

    (gen_random_uuid(),
     'PAYMENT_CONFIRMED', 'ko_KR', NULL,
     '결제가 완료되었습니다',
     '{{amount}}원 결제가 완료되었습니다.',
     TRUE, 1, NOW(), NOW()),

    (gen_random_uuid(),
     'CLASS_STARTS_TOMORROW', 'ko_KR', NULL,
     '내일 강의가 시작됩니다',
     '{{courseName}} 강의가 내일 시작됩니다.',
     TRUE, 1, NOW(), NOW());
