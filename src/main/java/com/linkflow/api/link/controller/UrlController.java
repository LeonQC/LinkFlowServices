package com.linkflow.api.link.controller;

import com.linkflow.api.link.dto.CreateShortUrlRequest;
import com.linkflow.api.link.dto.CreateShortUrlResponse;
import com.linkflow.api.link.service.UrlCodecService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/short-urls")
public class UrlController {

    private final UrlCodecService urlCodecService;

    public UrlController(UrlCodecService urlCodecService) {
        this.urlCodecService = urlCodecService;
    }

    @PostMapping
    public CreateShortUrlResponse create(@Valid @RequestBody CreateShortUrlRequest request) {
        var mapping = urlCodecService.createOrGet(request.longUrl());
        return new CreateShortUrlResponse(
                mapping.getSlug(),
                "/api/short-urls/" + mapping.getSlug(),
                mapping.getLongUrl()
        );
    }

    @GetMapping("/{slug}")
    public ResponseEntity<Void> shortToLong(@PathVariable String slug) {
        var mapping = urlCodecService.resolve(slug);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(mapping.getLongUrl()))
                .build();
    }
}
