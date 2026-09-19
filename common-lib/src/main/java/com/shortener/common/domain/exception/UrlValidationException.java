package com.shortener.common.domain.exception;
public class UrlValidationException extends BusinessException {
    public UrlValidationException(String message) {
        super("URL_VALIDATION_FAILED", message);
    }
}
