package com.linkflow.api.auth.service;

import com.linkflow.api.auth.domain.User;
import com.linkflow.api.auth.domain.UserStatus;
import com.linkflow.api.auth.dto.AuthUserResponse;
import com.linkflow.api.auth.dto.LoginRequest;
import com.linkflow.api.auth.dto.LoginResponse;
import com.linkflow.api.auth.dto.RefreshTokenRequest;
import com.linkflow.api.auth.dto.RegisterRequest;
import com.linkflow.api.auth.repository.UserRepository;
import com.linkflow.api.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        String normalizedUsername = request.username().trim();

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "USER_EMAIL_CONFLICT",
                    "Email is already registered.",
                    Map.of("email", normalizedEmail)
            );
        }

        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "USER_USERNAME_CONFLICT",
                    "Username is already taken.",
                    Map.of("username", normalizedUsername)
            );
        }

        User user = new User(normalizedEmail, normalizedUsername, passwordEncoder.encode(request.password()));
        return AuthUserResponse.from(userRepository.save(user));
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(this::invalidCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "USER_ACCOUNT_INACTIVE",
                    "User account is not active.",
                    Map.of("status", user.getStatus().name().toLowerCase(Locale.ROOT))
            );
        }

        refreshTokenService.revokeActiveTokens(user);
        return issueSession(user);
    }

    @Transactional
    public LoginResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.IssuedRefreshToken rotatedToken = refreshTokenService.rotate(request.refreshToken());
        return issueSession(rotatedToken.user(), rotatedToken);
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    private LoginResponse issueSession(User user) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return issueSession(user, refreshToken);
    }

    private LoginResponse issueSession(User user, RefreshTokenService.IssuedRefreshToken refreshToken) {
        JwtService.JwtAccessToken accessToken = jwtService.issueAccessToken(user);
        return LoginResponse.bearer(
                accessToken.tokenValue(),
                accessToken.expiresAt(),
                refreshToken.tokenValue(),
                refreshToken.expiresAt(),
                AuthUserResponse.from(user)
        );
    }

    private ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Email or password is incorrect."
        );
    }
}
