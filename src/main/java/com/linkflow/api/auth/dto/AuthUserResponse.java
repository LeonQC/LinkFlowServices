package com.linkflow.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linkflow.api.auth.domain.User;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

public record AuthUserResponse(
        UUID id,
        String email,
        String username,
        String role,
        String status,
        @JsonProperty("created_at")
        OffsetDateTime createdAt
) {
    public static AuthUserResponse from(User user) {
        return new AuthUserResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getRole().name().toLowerCase(Locale.ROOT),
                user.getStatus().name().toLowerCase(Locale.ROOT),
                user.getCreatedAt()
        );
    }
}
