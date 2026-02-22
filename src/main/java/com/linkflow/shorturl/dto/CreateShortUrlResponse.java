package com.linkflow.shorturl.dto;

public record CreateShortUrlResponse(
        String slug,
        String shortUrl,
        String longUrl
) {}
