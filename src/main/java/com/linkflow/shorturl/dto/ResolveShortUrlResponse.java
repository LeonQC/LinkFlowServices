package com.linkflow.shorturl.dto;

public record ResolveShortUrlResponse(
        String slug,
        String longUrl
) {}
