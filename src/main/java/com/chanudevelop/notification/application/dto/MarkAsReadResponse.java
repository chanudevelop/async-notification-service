package com.chanudevelop.notification.application.dto;

import java.util.UUID;

/**
 * 읽음 처리 API 응답.
 *
 * @param id 알림 ID
 * @param firstRead 이번 호출에서 처음 읽음 처리됐는가 (true = 1행 갱신, false = 이미 읽음 상태였음)
 */
public record MarkAsReadResponse(UUID id, boolean firstRead) {
}
