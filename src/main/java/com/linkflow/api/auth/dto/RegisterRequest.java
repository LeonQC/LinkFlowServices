package com.linkflow.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @NotBlank(message = "username is required")
        @Size(min = 3, max = 40, message = "username must be between 3 and 40 characters")
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,39}$",
                message = "username must start with a letter or number and contain only letters, numbers, dot, underscore, or hyphen"
        )
        String username,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        String password
) {
}
