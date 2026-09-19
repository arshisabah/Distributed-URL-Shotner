package com.shortener.url.api;

import com.shortener.common.domain.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;

/**
 * Centralised exception → HTTP response mapping.
 *
 * Uses RFC 7807 Problem Details (Spring 6 native support).
 * Never exposes stack traces or internal messages to clients.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ─── Domain exceptions ────────────────────────────────────────────────

    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(UrlNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("URL Not Found");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler({UrlExpiredException.class, UrlDeactivatedException.class})
    public ResponseEntity<ProblemDetail> handleGone(BusinessException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.GONE);
        pd.setTitle("URL No Longer Available");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.GONE).body(pd);
    }

    @ExceptionHandler(UrlValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(UrlValidationException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Invalid URL");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
    }

    @ExceptionHandler(BlockedDomainException.class)
    public ResponseEntity<ProblemDetail> handleBlocked(BlockedDomainException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Domain Not Allowed");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
    }

    @ExceptionHandler(AliasAlreadyTakenException.class)
    public ResponseEntity<ProblemDetail> handleAliasConflict(AliasAlreadyTakenException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Alias Unavailable");
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex,
                                                          HttpServletResponse response) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        pd.setTitle("Rate Limit Exceeded");
        pd.setDetail(ex.getMessage());
        pd.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());
        response.setHeader("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(pd);
    }

    @ExceptionHandler(InvalidShortCodeException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCode(InvalidShortCodeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Invalid Short Code");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidToken(InvalidTokenException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("Invalid Token");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ProblemDetail> handleAuthFailed(AuthenticationFailedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("Authentication Failed");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
    }

    // ─── Spring validation ─────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBindValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));

        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation Failed");
        pd.setDetail(details);
        pd.setProperty("fields", ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage)));
        return ResponseEntity.badRequest().body(pd);
    }

    // ─── Spring Security ──────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("Access Denied");
        pd.setDetail("You do not have permission to perform this action");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    // ─── Catch-all ────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex,
                                                        HttpServletRequest req) {
        String reqId = MDC.get("requestId");
        log.error("Unhandled exception [requestId={}] on {} {}: {}",
            reqId, req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);

        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal Server Error");
        pd.setDetail("An unexpected error occurred. Reference: " + reqId);
        pd.setProperty("requestId", reqId);
        // Never expose internal details (stack trace, class names) to clients
        return ResponseEntity.internalServerError().body(pd);
    }
}
