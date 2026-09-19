package com.shortener.url.infrastructure.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.shortener.url.domain.User.UserTier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * JWT authentication filter — stateless, runs on every request.
 *
 * Flow:
 *   1. Extract "Bearer <token>" from Authorization header
 *   2. Verify RS256 signature using public key (never needs private key)
 *   3. Check jti revocation list in Redis (for logout support)
 *   4. Populate SecurityContext with JwtUserDetails
 *
 * If no token / invalid token: request continues as anonymous.
 * Endpoints that require auth enforce it via @PreAuthorize.
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final RSAPublicKey         publicKey;
    private final StringRedisTemplate  redis;

    public JwtAuthenticationFilter(RSAPublicKey jwtPublicKey,
                                    StringRedisTemplate redis) {
        this.publicKey = jwtPublicKey;
        this.redis     = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest  req,
                                    HttpServletResponse res,
                                    FilterChain         chain)
            throws ServletException, IOException {

        String header = req.getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(req, res);   // anonymous — let security config decide
            return;
        }

        String token = header.substring(7);

        try {
            DecodedJWT jwt = JWT.require(Algorithm.RSA256(publicKey, null))
                .withIssuer("shortener-auth-service")
                .withAudience("shortener-api")
                .build()
                .verify(token);

            // Check revocation list
            String jti = jwt.getClaim("jti").asString();
            if (jti != null && Boolean.TRUE.equals(redis.hasKey("revoked_jti:" + jti))) {
                log.debug("Revoked JWT presented: jti={}", jti);
                chain.doFilter(req, res);
                return;
            }

            JwtUserDetails principal = buildPrincipal(jwt);

            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null,
                    principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (JWTVerificationException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            // Do NOT return 401 here — let downstream decide (some endpoints are public)
        }

        chain.doFilter(req, res);
    }

    private JwtUserDetails buildPrincipal(DecodedJWT jwt) {
        String tierStr = jwt.getClaim("tier").asString();
        UserTier tier;
        try {
            tier = (tierStr != null) ? UserTier.valueOf(tierStr) : UserTier.FREE;
        } catch (IllegalArgumentException e) {
            tier = UserTier.FREE;
        }

        List<String> roles = jwt.getClaim("roles").asList(String.class);
        if (roles == null) roles = List.of("USER");

        return JwtUserDetails.builder()
            .userId(Long.parseLong(jwt.getSubject()))
            .email(jwt.getClaim("email").asString())
            .tier(tier)
            .roles(roles)
            .build();
    }
}
