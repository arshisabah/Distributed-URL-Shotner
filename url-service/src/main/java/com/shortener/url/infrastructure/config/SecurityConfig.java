package com.shortener.url.infrastructure.config;

import com.shortener.url.infrastructure.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Spring Security configuration for url-service.
 *
 * Auth strategy: stateless JWT (RS256).
 *   - No sessions; every request carries the JWT.
 *   - Public key only — url-service never needs the private key.
 *   - Anonymous access allowed on redirect + creation endpoints; enforced
 *     per-endpoint via @PreAuthorize where needed.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            // Stateless REST API — no CSRF, no sessions
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s ->
                s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(c -> c.configurationSource(corsSource()))

            // Route-level access rules
            .authorizeHttpRequests(auth -> auth
                // Public: redirects, health, docs, anonymous URL creation
                .requestMatchers(HttpMethod.GET, "/{shortCode:[0-9a-zA-Z]{4,10}}").permitAll()
                .requestMatchers(HttpMethod.GET, "/{shortCode}/preview").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/urls/{shortCode}/qr").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/urls").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info",
                                 "/actuator/prometheus").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )

            // JWT filter runs before Spring's username/password filter
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)

            // Return 401 (not redirect to login page) for unauthenticated REST calls
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((req, res, denied) ->
                    res.setStatus(HttpStatus.FORBIDDEN.value()))
            )
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost factor 12 → ~250 ms per hash (adequate brute-force protection)
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public RSAPublicKey jwtPublicKey(@Value("${jwt.public-key}") String pem) throws Exception {
        // Strip PEM headers and whitespace
        String stripped = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(stripped);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
