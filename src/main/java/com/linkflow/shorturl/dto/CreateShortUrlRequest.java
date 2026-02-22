package com.linkflow.shorturl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateShortUrlRequest(
        @NotBlank(message = "longUrl must not be blank")
        @Size(max = 2048, message = "longUrl length must be <= 2048")
        @URL(message = "longUrl must be a valid URL")
        String longUrl
) {}
