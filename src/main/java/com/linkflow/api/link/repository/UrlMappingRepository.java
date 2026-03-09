package com.linkflow.api.link.repository;

import com.linkflow.api.link.domain.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    Optional<UrlMapping> findByLongUrl(String longUrl);

    Optional<UrlMapping> findBySlug(String slug);
}
