package com.linkflow.api.auth.service;

import com.linkflow.api.auth.domain.User;
import com.linkflow.api.auth.dto.LoginRequest;
import com.linkflow.api.auth.dto.LoginResponse;
import com.linkflow.api.auth.dto.RefreshTokenRequest;
import com.linkflow.api.auth.repository.UserRepository;
import com.linkflow.api.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginReturnsBearerTokenForValidCredentials() {
        User user = new User("alice@example.com", "alice", "encoded-password");
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "encoded-password")).thenReturn(true);
        when(refreshTokenService.issue(any(User.class))).thenReturn(
                new RefreshTokenService.IssuedRefreshToken(
                        "refresh-token-value",
                        OffsetDateTime.parse("2026-03-14T11:00:00Z"),
                        user
                )
        );
        when(jwtService.issueAccessToken(user)).thenReturn(
                new JwtService.JwtAccessToken("jwt-token-value", OffsetDateTime.parse("2026-03-07T11:00:00Z"))
        );

        LoginResponse response = authService.login(new LoginRequest("alice@example.com", "secret123"));

        assertEquals("jwt-token-value", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals("refresh-token-value", response.refreshToken());
        assertEquals("alice@example.com", response.user().email());
    }

    @Test
    void loginRejectsInvalidCredentials() {
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.empty());

        ApiException exception = assertThrows(
                ApiException.class,
                () -> authService.login(new LoginRequest("alice@example.com", "secret123"))
        );

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("INVALID_CREDENTIALS", exception.getCode());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(jwtService, never()).issueAccessToken(any());
    }

    @Test
    void refreshRotatesRefreshTokenAndIssuesNewAccessToken() {
        User user = new User("alice@example.com", "alice", "encoded-password");
        when(refreshTokenService.rotate("old-refresh-token")).thenReturn(
                new RefreshTokenService.IssuedRefreshToken(
                        "new-refresh-token",
                        OffsetDateTime.parse("2026-03-14T12:00:00Z"),
                        user
                )
        );
        when(jwtService.issueAccessToken(user)).thenReturn(
                new JwtService.JwtAccessToken("new-access-token", OffsetDateTime.parse("2026-03-07T12:00:00Z"))
        );

        LoginResponse response = authService.refresh(new RefreshTokenRequest("old-refresh-token"));

        assertEquals("new-access-token", response.accessToken());
        assertEquals("new-refresh-token", response.refreshToken());
        assertEquals("alice@example.com", response.user().email());
    }
}
