package com.shortener.common.domain.exception;
public class InvalidShortCodeException extends BusinessException {
    public InvalidShortCodeException(String detail) {
        super("INVALID_SHORT_CODE", detail);
    }
}
