package com.chanudevelop.notification.presentation;

import com.chanudevelop.notification.application.NotificationService;
import com.chanudevelop.notification.application.dto.NotificationCreateRequest;
import com.chanudevelop.notification.application.dto.NotificationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ResponseEntity<NotificationResponse> create(
            @Valid @RequestBody NotificationCreateRequest request
    ) {
        NotificationResponse response = notificationService.create(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
