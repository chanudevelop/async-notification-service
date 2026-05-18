package com.chanudevelop.notification.presentation;

import com.chanudevelop.notification.application.NotificationService;
import com.chanudevelop.notification.application.dto.NotificationCreateRequest;
import com.chanudevelop.notification.application.dto.NotificationDetailResponse;
import com.chanudevelop.notification.application.dto.NotificationListItem;
import com.chanudevelop.notification.application.dto.NotificationResponse;
import com.chanudevelop.notification.application.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 알림 등록 — 외부 시스템 호출.
     */
    @PostMapping
    public ResponseEntity<NotificationResponse> create(
            @Valid @RequestBody NotificationCreateRequest request
    ) {
        NotificationResponse response = notificationService.create(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 알림 상세 조회 — 시스템 간 호출 / 운영자 디버깅용.
     * 인증 헤더 미사용.
     */
    @GetMapping("/{id}")
    public ResponseEntity<NotificationDetailResponse> getDetail(@PathVariable UUID id) {
        NotificationDetailResponse response = notificationService.getDetail(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 본인 알림 목록 조회 — 끝 사용자 호출.
     * X-User-Id 헤더로 본인 식별.
     */
    @GetMapping("/me")
    public ResponseEntity<PageResponse<NotificationListItem>> getMyNotifications(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(value = "read", required = false) Boolean read,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        PageResponse<NotificationListItem> response =
                notificationService.getMyNotifications(userId, read, pageable);
        return ResponseEntity.ok(response);
    }
}
