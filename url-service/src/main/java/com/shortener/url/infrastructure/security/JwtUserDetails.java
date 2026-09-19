package com.shortener.url.infrastructure.security;

import com.shortener.url.domain.User.UserTier;
import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security principal populated from JWT claims.
 * Carried on every authenticated request via SecurityContextHolder.
 */
@Builder
@Getter
public class JwtUserDetails implements UserDetails {

    private Long       userId;
    private String     email;
    private String     username;
    private UserTier   tier;
    private List<String> roles;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
            .map(r -> new SimpleGrantedAuthority(r.startsWith("ROLE_") ? r : "ROLE_" + r))
            .toList();
    }

    @Override public String getPassword()                { return null; }
    @Override public String getUsername()                { return email; }
    @Override public boolean isAccountNonExpired()       { return true; }
    @Override public boolean isAccountNonLocked()        { return true; }
    @Override public boolean isCredentialsNonExpired()   { return true; }
    @Override public boolean isEnabled()                 { return true; }

    /** Rate-limit burst capacity based on subscription tier. */
    public int getBurstCapacity() {
        return switch (tier) {
            case FREE       -> 10;
            case PRO        -> 100;
            case ENTERPRISE -> Integer.MAX_VALUE;
        };
    }

    /** Token refill rate (tokens/second) based on subscription tier. */
    public int getRefillRate() {
        return switch (tier) {
            case FREE       -> 1;    //  60/min
            case PRO        -> 2;    // 120/min
            case ENTERPRISE -> 100;  // unlimited effective
        };
    }
}
