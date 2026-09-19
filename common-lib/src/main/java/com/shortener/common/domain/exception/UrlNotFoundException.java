package com.shortener.common.domain.exception;
public class UrlNotFoundException extends BusinessException {
    public UrlNotFoundException(String shortCode) {
        super("URL_NOT_FOUND", "No active URL found for short code: " + shortCode);
    }
}
