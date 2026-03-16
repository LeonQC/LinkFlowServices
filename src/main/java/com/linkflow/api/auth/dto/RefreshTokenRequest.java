package com.linkflow.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(
        @JsonProperty("refresh_token")
        @NotBlank(message = "refresh_token is required")
        @Size(max = 128, message = "refresh_token must be at most 128 characters")
        String refreshToken
) {
}
