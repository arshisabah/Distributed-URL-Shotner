package com.shortener.common.domain.exception;
public class UrlDeactivatedException extends BusinessException {
    public UrlDeactivatedException(String shortCode) {
        super("URL_DEACTIVATED", "URL has been deactivated: " + shortCode);
    }
}
