package com.shortener.common.domain.exception;
public class AliasAlreadyTakenException extends BusinessException {
    public AliasAlreadyTakenException(String alias) {
        super("ALIAS_TAKEN", "Custom alias is already in use: " + alias);
    }
}
