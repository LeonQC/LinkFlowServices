package com.linkflow.shorturl.service;

import com.linkflow.shorturl.domain.UrlMapping;
import com.linkflow.shorturl.repository.UrlMappingRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.NoSuchElementException;

@Service
public class UrlCodecService {

    private static final int SLUG_LENGTH = 7;
    private static final int MAX_RETRY = 10;
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"; // 短码字符集（62进制）

    private final UrlMappingRepository repository;
    private final SecureRandom random = new SecureRandom();

    public UrlCodecService(UrlMappingRepository repository) {
        this.repository = repository;
    }

    // 写操作事务：查+写要在一致上下文中执行
    @Transactional
    public UrlMapping createOrGet(String longUrl) {
        return repository.findByLongUrl(longUrl)
                .orElseGet(() -> createWithRetry(longUrl));
    }

    @Transactional(readOnly = true) // 只读事务，优化查询语义
    public UrlMapping resolve(String slug) {
        return repository.findBySlug(slug)
                .orElseThrow(() -> new NoSuchElementException("short url not found"));
    }

    private UrlMapping createWithRetry(String longUrl) {
        for (int i = 0; i < MAX_RETRY; i++) {
            String slug = generateSlug();
            try {
                return repository.save(new UrlMapping(longUrl, slug));
            } catch (DataIntegrityViolationException ex) {
                var existing = repository.findByLongUrl(longUrl);
                if (existing.isPresent()) {
                    return existing.get();
                }

            }
        }
        throw new IllegalStateException("failed to generate unique slug after retries");
    }

    private String generateSlug() {
        StringBuilder sb = new StringBuilder(SLUG_LENGTH);
        for (int i = 0; i < SLUG_LENGTH; i++) {
            int idx = random.nextInt(ALPHABET.length());
            sb.append(ALPHABET.charAt(idx));
        }
        return sb.toString();
    }
}
