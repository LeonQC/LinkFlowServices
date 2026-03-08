package com.linkflow.api.common.response;

import com.linkflow.api.common.web.RequestIdContext;

import java.util.Map;

public record ApiResponse<T>(
        String request_id,
        T data,
        ApiError error,
        Map<String, Object> meta
) {
    public ApiResponse {
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(RequestIdContext.getRequestId(), data, null, Map.of());
    }

    public static <T> ApiResponse<T> success(T data, Map<String, Object> meta) {
        return new ApiResponse<>(RequestIdContext.getRequestId(), data, null, meta);
    }

    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(RequestIdContext.getRequestId(), null, error, Map.of());
    }
}
