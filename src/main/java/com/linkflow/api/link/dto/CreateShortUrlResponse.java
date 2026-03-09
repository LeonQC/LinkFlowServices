package com.linkflow.api.link.dto;

public record CreateShortUrlResponse(
        String slug,
        String shortUrl,
        String longUrl
) {
}
