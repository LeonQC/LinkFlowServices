package com.linkflow.shorturl.controller;

import com.linkflow.shorturl.dto.CreateShortUrlRequest;
import com.linkflow.shorturl.dto.CreateShortUrlResponse;
import com.linkflow.shorturl.dto.ResolveShortUrlResponse;
import com.linkflow.shorturl.service.UrlCodecService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/short-urls")
public class UrlController {

    private final UrlCodecService urlCodecService;

    public UrlController(UrlCodecService urlCodecService) {
        this.urlCodecService = urlCodecService;
    }

    @PostMapping
    public CreateShortUrlResponse create(@Valid @RequestBody CreateShortUrlRequest request){
        var mapping = urlCodecService.createOrGet(request.longUrl());
        return new CreateShortUrlResponse(
                mapping.getSlug(),
                "/api/short-urls/" + mapping.getSlug(),
                mapping.getLongUrl()
        );
    }

    @GetMapping("/{slug}")
    public ResolveShortUrlResponse resolveShortUrl(@PathVariable String slug){
        var mapping = urlCodecService.resolve(slug);
        return new ResolveShortUrlResponse(mapping.getSlug(), mapping.getLongUrl());
    }
}
