package com.shortener.common.domain.exception;

/**
 * Base class for all domain/business exceptions.
 * Maps to HTTP 4xx — never exposes stack traces to clients.
 */
public abstract class BusinessException extends RuntimeException {
    private final String errorCode;

    protected BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
