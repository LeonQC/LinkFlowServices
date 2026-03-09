package com.linkflow.api.auth.controller;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkflow.api.auth.dto.AuthUserResponse;
import com.linkflow.api.auth.dto.RegisterRequest;
import com.linkflow.api.auth.service.AuthService;
import com.linkflow.api.common.exception.ApiExceptionHandler;
import com.linkflow.api.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockMvc createMockMvc(AuthService authService) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        return MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void registerReturnsEnvelope() throws Exception {
        AuthService authService = Mockito.mock(AuthService.class);
        MockMvc mockMvc = createMockMvc(authService);

        AuthUserResponse response = new AuthUserResponse(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "alice@example.com",
                "alice",
                "user",
                "active",
                OffsetDateTime.parse("2026-03-07T10:00:00Z")
        );

        Mockito.when(authService.register(Mockito.any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                "alice@example.com",
                                "alice",
                                "secret123"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.role").value("user"))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void registerValidatesPayload() throws Exception {
        MockMvc mockMvc = createMockMvc(Mockito.mock(AuthService.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bad-email",
                                  "username": "a",
                                  "password": "123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.email").exists())
                .andExpect(jsonPath("$.error.details.username").exists())
                .andExpect(jsonPath("$.error.details.password").exists());
    }
}
