package com.chanudevelop.notification.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        String path,
        LocalDateTime timestamp,
        List<FieldError> errors
) {

    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, path, LocalDateTime.now(), null);
    }

    public static ErrorResponse of(String code, String message, String path, List<FieldError> errors) {
        return new ErrorResponse(code, message, path, LocalDateTime.now(), errors);
    }

    public record FieldError(String field, String message) {
    }
}
