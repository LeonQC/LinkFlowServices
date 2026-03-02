package com.linkflow.shorturl.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.linkflow.shorturl.domain.UrlMapping;

import java.util.Optional;


public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long>{
    Optional<UrlMapping> findByLongUrl(String longUrl);
    Optional<UrlMapping> findBySlug(String slug);
}
