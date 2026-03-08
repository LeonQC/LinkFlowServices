package com.linkflow.api.auth.service;

import com.linkflow.api.auth.domain.User;
import com.linkflow.api.auth.dto.AuthUserResponse;
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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
}
