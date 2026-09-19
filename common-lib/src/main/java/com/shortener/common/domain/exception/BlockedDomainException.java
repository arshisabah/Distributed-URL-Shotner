package com.shortener.common.domain.exception;
public class BlockedDomainException extends BusinessException {
    public BlockedDomainException(String domain) {
        super("DOMAIN_BLOCKED", "Domain is not allowed: " + domain);
    }
}
