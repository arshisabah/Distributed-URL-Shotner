package com.shortener.common.domain.exception;
public class UrlExpiredException extends BusinessException {
    public UrlExpiredException(String shortCode) {
        super("URL_EXPIRED", "URL has expired: " + shortCode);
    }
}
