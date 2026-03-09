package com.linkflow.api.link.dto;

public record ResolveShortUrlResponse(
        String slug,
        String longUrl
) {
}
