package com.shortener.common.domain.exception;
public class AuthenticationFailedException extends BusinessException {
    public AuthenticationFailedException(String message) {
        super("AUTHENTICATION_FAILED", message);
    }
}
