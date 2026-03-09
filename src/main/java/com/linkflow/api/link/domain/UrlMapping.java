package com.linkflow.api.link.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "url_mapping")
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "long_url", nullable = false, unique = true, columnDefinition = "TEXT")
    private String longUrl;

    @Column(name = "slug", nullable = false, unique = true, length = 16)
    private String slug;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected UrlMapping() {
    }

    public UrlMapping(String longUrl, String slug) {
        this.slug = slug;
        this.longUrl = longUrl;
    }

    public Long getId() {
        return id;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public String getSlug() {
        return slug;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
