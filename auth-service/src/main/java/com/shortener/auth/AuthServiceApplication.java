package com.shortener.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.shortener.common.domain.exception.AuthenticationFailedException;
import com.shortener.common.domain.exception.InvalidTokenException;
import com.shortener.common.domain.exception.UserNotFoundException;
import com.shortener.common.util.SnowflakeIdGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

// ─── Application ─────────────────────────────────────────────────────────────

@SpringBootApplication(scanBasePackages = "com.shortener")
@EnableJpaAuditing
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}

// ─── Domain Entity: User ─────────────────────────────────────────────────────

@Entity
@Table(name = "users",
    indexes = {
        @Index(name = "idx_auth_users_email",    columnList = "email",    unique = true),
        @Index(name = "idx_auth_users_username", columnList = "username", unique = true)
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
class AuthUser {
    @Id private Long id;
    @Column(nullable = false, unique = true) private String email;
    @Column(nullable = false, unique = true) private String username;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(nullable = false) @Builder.Default private String tier = "FREE";
    @Column(nullable = false) @Builder.Default private String status = "ACTIVE";
    @Column(name = "email_verified", nullable = false) @Builder.Default private boolean emailVerified = true;
    @CreatedDate @Column(name = "created_at", updatable = false) private Instant createdAt;
}

// ─── Domain Entity: RefreshToken ──────────────────────────────────────────────

@Entity
@Table(name = "refresh_tokens")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
class RefreshToken {
    @Id private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "token_hash", nullable = false, unique = true) private String tokenHash;
    @Column(name = "device_info") private String deviceInfo;
    @Column(name = "expires_at",  nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at")  private Instant revokedAt;
    @CreatedDate @Column(name = "created_at", updatable = false) private Instant createdAt;

    public boolean isRevoked()  { return revokedAt != null; }
    public boolean isExpired()  { return Instant.now().isAfter(expiresAt); }
}

// ─── Repositories ────────────────────────────────────────────────────────────

@Repository
interface AuthUserRepository extends JpaRepository<AuthUser, Long> {
    Optional<AuthUser> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);
}

@Repository
interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String hash);
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(Long userId, Instant now);
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 3, max = 30) String username,
    @NotBlank @Size(min = 8) String password
) {}

record LoginRequest(
    @NotBlank String email,
    @NotBlank String password
) {}

record TokenResponse(
    String accessToken,
    String refreshToken,
    long   expiresIn,
    String tokenType
) {}

record RefreshRequest(@NotBlank String refreshToken) {}

// ─── JWT Token Provider ──────────────────────────────────────────────────────

@Service
@Slf4j
class JwtTokenProvider {

    static final Duration ACCESS_TTL  = Duration.ofHours(1);
    static final Duration REFRESH_TTL = Duration.ofDays(30);

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey  publicKey;
    private final StringRedisTemplate redis;

    JwtTokenProvider(
            @org.springframework.beans.factory.annotation.Value("${jwt.private-key}") String privPem,
            @org.springframework.beans.factory.annotation.Value("${jwt.public-key}")  String pubPem,
            StringRedisTemplate redis) throws Exception {
        this.privateKey = loadPrivateKey(privPem);
        this.publicKey  = loadPublicKey(pubPem);
        this.redis = redis;
    }

    String generateAccessToken(AuthUser user) {
        Instant now = Instant.now();
        return JWT.create()
            .withSubject(String.valueOf(user.getId()))
            .withClaim("email", user.getEmail())
            .withClaim("tier",  user.getTier())
            .withClaim("roles", List.of("USER"))
            .withClaim("jti",   UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(ACCESS_TTL)))
            .withIssuer("shortener-auth-service")
            .withAudience("shortener-api")
            .sign(Algorithm.RSA256(publicKey, privateKey));
    }

    void revokeToken(String jti, Instant expiry) {
        Duration ttl = Duration.between(Instant.now(), expiry);
        if (!ttl.isNegative()) {
            redis.opsForValue().set("revoked_jti:" + jti, "1", ttl);
        }
    }

    private RSAPrivateKey loadPrivateKey(String pem) throws Exception {
        String stripped = pem.replaceAll("-----.*?-----", "").replaceAll("\\s+", "");
        byte[] decoded  = Base64.getDecoder().decode(stripped);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private RSAPublicKey loadPublicKey(String pem) throws Exception {
        String stripped = pem.replaceAll("-----.*?-----", "").replaceAll("\\s+", "");
        byte[] decoded  = Base64.getDecoder().decode(stripped);
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(decoded));
    }
}

// ─── Auth Application Service ────────────────────────────────────────────────

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
class AuthApplicationService {

    private final AuthUserRepository    userRepo;
    private final RefreshTokenRepository tokenRepo;
    private final JwtTokenProvider       jwtProvider;
    private final PasswordEncoder        encoder;
    private final SnowflakeIdGenerator   snowflake;

    TokenResponse register(RegisterRequest req) {
        if (userRepo.existsByEmailIgnoreCase(req.email()))
            throw new AuthenticationFailedException("Email already registered");
        if (userRepo.existsByUsernameIgnoreCase(req.username()))
            throw new AuthenticationFailedException("Username already taken");

        AuthUser user = userRepo.save(AuthUser.builder()
            .id(snowflake.nextId())
            .email(req.email().toLowerCase())
            .username(req.username())
            .passwordHash(encoder.encode(req.password()))
            .emailVerified(true) // Skip email verification for simplicity
            .build());

        log.info("User registered: id={} email={}", user.getId(), user.getEmail());
        return issueTokens(user, "web");
    }

    TokenResponse login(LoginRequest req) {
        AuthUser user = userRepo.findByEmailIgnoreCase(req.email())
            .orElseThrow(() -> new AuthenticationFailedException("Invalid credentials"));

        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new AuthenticationFailedException("Invalid credentials");
        }

        log.info("User logged in: id={}", user.getId());
        return issueTokens(user, "web");
    }

    TokenResponse refresh(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken stored = tokenRepo.findByTokenHash(hash)
            .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));

        if (stored.isRevoked()) {
            // Token reuse detected — revoke ALL sessions for this user
            tokenRepo.revokeAllForUser(stored.getUserId(), Instant.now());
            throw new InvalidTokenException("Refresh token reuse detected — all sessions revoked");
        }
        if (stored.isExpired()) throw new InvalidTokenException("Refresh token expired");

        stored.setRevokedAt(Instant.now());
        tokenRepo.save(stored);

        AuthUser user = userRepo.findById(stored.getUserId())
            .orElseThrow(() -> new UserNotFoundException(stored.getUserId()));
        return issueTokens(user, "refresh");
    }

    void logout(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        tokenRepo.findByTokenHash(hash).ifPresent(t -> {
            t.setRevokedAt(Instant.now());
            tokenRepo.save(t);
        });
    }

    private TokenResponse issueTokens(AuthUser user, String device) {
        String accessToken = jwtProvider.generateAccessToken(user);

        byte[] rawBytes = new byte[32];
        new SecureRandom().nextBytes(rawBytes);
        String rawRefresh = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);

        tokenRepo.save(RefreshToken.builder()
            .id(snowflake.nextId())
            .userId(user.getId())
            .tokenHash(sha256(rawRefresh))
            .deviceInfo(device)
            .expiresAt(Instant.now().plus(JwtTokenProvider.REFRESH_TTL))
            .build());

        return new TokenResponse(accessToken, rawRefresh,
            JwtTokenProvider.ACCESS_TTL.getSeconds(), "Bearer");
    }

    private String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

// ─── Auth Controller ─────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register, login, refresh, logout")
class AuthController {

    private final AuthApplicationService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user account")
    TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email + password")
    TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access token")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the current refresh token")
    void logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
    }

    @ExceptionHandler({AuthenticationFailedException.class, InvalidTokenException.class})
    ResponseEntity<ProblemDetail> handleAuth(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("Authentication Error");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
    }
}

// ─── Security Config ─────────────────────────────────────────────────────────

@Configuration
@EnableWebSecurity
class AuthSecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**",
                                 "/swagger-ui/**").permitAll()
                .anyRequest().authenticated())
            .build();
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}
