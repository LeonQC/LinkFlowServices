package com.linkflow.shorturl.config; // security config package

import org.springframework.context.annotation.Bean; // define spring bean
import org.springframework.context.annotation.Configuration; // mark config class
import org.springframework.security.config.Customizer; // default customizer helper
import org.springframework.security.config.annotation.web.builders.HttpSecurity; // security http builder
import org.springframework.security.web.SecurityFilterChain; // security filter chain type

@Configuration // register this class as configuration
public class SecurityConfig { // security configuration class

    @Bean // expose filter chain as bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception { // build http security rules
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/short-urls").permitAll()
                        .requestMatchers("/api/short-urls/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}