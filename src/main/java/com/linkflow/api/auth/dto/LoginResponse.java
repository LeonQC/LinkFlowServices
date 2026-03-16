package com.linkflow.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record LoginResponse(
        @JsonProperty("access_token")
        String accessToken,
        @JsonProperty("token_type")
        String tokenType,
        @JsonProperty("expires_at")
        OffsetDateTime expiresAt,
        @JsonProperty("refresh_token")
        String refreshToken,
        @JsonProperty("refresh_expires_at")
        OffsetDateTime refreshExpiresAt,
        AuthUserResponse user
) {
    public static LoginResponse bearer(
            String accessToken,
            OffsetDateTime expiresAt,
            String refreshToken,
            OffsetDateTime refreshExpiresAt,
            AuthUserResponse user
    ) {
        return new LoginResponse(accessToken, "Bearer", expiresAt, refreshToken, refreshExpiresAt, user);
    }
}
