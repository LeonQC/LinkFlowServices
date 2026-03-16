package com.linkflow.api.auth.service;

import com.linkflow.api.auth.domain.RefreshToken;
import com.linkflow.api.auth.domain.User;
import com.linkflow.api.auth.repository.RefreshTokenRepository;
import com.linkflow.api.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Duration refreshTokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${app.security.jwt.refresh-token-ttl}") Duration refreshTokenTtl
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    @Transactional
    public IssuedRefreshToken issue(User user) {
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(refreshTokenTtl);
        RefreshToken refreshToken = refreshTokenRepository.save(
                new RefreshToken(user, generateTokenValue(), expiresAt)
        );

        return new IssuedRefreshToken(refreshToken.getToken(), refreshToken.getExpiresAt(), refreshToken.getUser());
    }

    @Transactional
    public IssuedRefreshToken rotate(String rawToken) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(rawToken)
                .orElseThrow(this::invalidRefreshToken);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!refreshToken.isActive(now)) {
            throw invalidRefreshToken();
        }

        refreshToken.revoke(now);
        RefreshToken replacement = refreshTokenRepository.save(
                new RefreshToken(refreshToken.getUser(), generateTokenValue(), now.plus(refreshTokenTtl))
        );

        return new IssuedRefreshToken(replacement.getToken(), replacement.getExpiresAt(), replacement.getUser());
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByToken(rawToken).ifPresent(token -> token.revoke(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Transactional
    public void revokeActiveTokens(User user) {
        List<RefreshToken> tokens = refreshTokenRepository.findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(
                user.getId(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        OffsetDateTime revokedAt = OffsetDateTime.now(ZoneOffset.UTC);
        tokens.forEach(token -> token.revoke(revokedAt));
    }

    private String generateTokenValue() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ApiException invalidRefreshToken() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN",
                "Refresh token is invalid or expired.",
                Map.of()
        );
    }

    public record IssuedRefreshToken(String tokenValue, OffsetDateTime expiresAt, User user) {
    }
}
