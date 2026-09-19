package com.shortener.url.application.dto;

import lombok.Builder;

@Builder
public record ResolveUrlResult(
    String  originalUrl,
    boolean requiresPassword,
    String  shortCode
) {
    public static ResolveUrlResult redirect(String url) {
        return ResolveUrlResult.builder().originalUrl(url).requiresPassword(false).build();
    }
    public static ResolveUrlResult passwordRequired(String code) {
        return ResolveUrlResult.builder().shortCode(code).requiresPassword(true).build();
    }
}
