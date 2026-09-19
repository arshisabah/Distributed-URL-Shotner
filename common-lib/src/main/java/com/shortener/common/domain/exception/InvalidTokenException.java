package com.shortener.common.domain.exception;
public class InvalidTokenException extends BusinessException {
    public InvalidTokenException(String message) {
        super("INVALID_TOKEN", message);
    }
}
