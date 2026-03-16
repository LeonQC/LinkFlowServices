package com.linkflow.api.auth.repository;

import com.linkflow.api.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    List<RefreshToken> findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(UUID userId, OffsetDateTime now);
}
