package com.liverpool.ms_home.adapter.inbound.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import jakarta.validation.ConstraintViolationException;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.liverpool.ms_home.domain.error.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_home.domain.error.ErrorCodes;
import com.liverpool.ms_home.domain.error.HomeException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Single translation point from domain exceptions to RFC 7807 {@link ProblemDetail} HTTP responses
 * (Rule 12 — never throw unchecked exceptions for recoverable business errors; no per-type logic in
 * HTTP adapters).
 *
 * <p>All domain exception semantics (status code, error code, category, retryability) are carried
 * on the exception itself so this class adds no business logic — it only translates. Logging is
 * centralised here (log-once at the highest layer, Rule 11).</p>
 *
 * <h3>Handler order:</h3>
 * <ol>
 *   <li>{@link DynamicBlockServiceUnavailableException} — most specific; includes block context.</li>
 *   <li>{@link HomeException} — covers all other domain errors.</li>
 *   <li>{@link HandlerMethodValidationException} — Spring 6+ validation failures on path/query params.</li>
 *   <li>{@link Exception} — catch-all; always 500, never leaks internal detail.</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROP_ERROR_CODE = "errorCode";
    private static final String PROP_CATEGORY = "category";
    private static final String PROP_RETRYABLE = "retryable";
    private static final String PROP_BLOCK_ID = "blockId";
    private static final String PROP_BLOCK_TYPE = "blockType";

    /**
     * Handles dynamic-block service failures, adding block context so the frontend can target the
     * exact placeholder that failed without affecting the rest of the page render.
     *
     * @param ex      the block-level service failure
     * @param request the inbound request (for logging context)
     * @return 502 ProblemDetail with blockId and blockType properties
     */
    @ExceptionHandler(DynamicBlockServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDynamicBlockUnavailable(
            DynamicBlockServiceUnavailableException ex, HttpServletRequest request) {
        log.warn("Dynamic block unavailable uri={} blockId={} blockType={} cause={}",
                request.getRequestURI(), ex.getBlockId(), ex.getBlockType(), ex.getMessage());

        ProblemDetail problem = buildBaseProblem(ex);
        problem.setProperty(PROP_BLOCK_ID, ex.getBlockId());
        problem.setProperty(PROP_BLOCK_TYPE, ex.getBlockType());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /**
     * Handles all other domain exceptions (validation, not-found, content-service unavailable,
     * circuit-breaker open). The HTTP status, error code, category, and retryability are taken
     * directly from the exception — no switch/if chain needed here (Rule 12).
     *
     * @param ex      any {@link HomeException} not handled by a more specific method
     * @param request the inbound request (for logging context)
     * @return ProblemDetail with the exception's status and properties
     */
    @ExceptionHandler(HomeException.class)
    public ResponseEntity<ProblemDetail> handleHomeException(HomeException ex, HttpServletRequest request) {
        if (ex.getStatus() >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            log.error("Domain error uri={} errorCode={} status={} detail={}",
                    request.getRequestURI(), ex.getErrorCode(), ex.getStatus(), ex.getDetail());
        } else {
            log.warn("Domain error uri={} errorCode={} status={} message={}",
                    request.getRequestURI(), ex.getErrorCode(), ex.getStatus(), ex.getMessage());
        }

        return ResponseEntity.status(ex.getStatus()).body(buildBaseProblem(ex));
    }

    /**
     * Handles Spring MVC method-level validation failures ({@code @Validated} on controller +
     * {@code @NotBlank}/{@code @Size} on path/query params). Always returns HTTP 400.
     *
     * @param ex      the validation failure
     * @param request the inbound request (for logging context)
     * @return 400 ProblemDetail with a stable validation error code
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        log.warn("Validation failure uri={} message={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(buildValidationProblem());
    }

    /**
     * Handles bean-validation constraint violations raised by the AOP {@code MethodValidationInterceptor}
     * ({@code @Validated} on Spring beans). Always returns HTTP 400.
     *
     * @param ex      the constraint violation
     * @param request the inbound request (for logging context)
     * @return 400 ProblemDetail with a stable validation error code
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        log.warn("Constraint violation uri={} message={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(buildValidationProblem());
    }

    /**
     * Catch-all for any unhandled exception. Logs at ERROR level and returns a generic 500 without
     * leaking internal implementation details to the client (security — Rule 12).
     *
     * @param ex      the unhandled throwable
     * @param request the inbound request (for logging context)
     * @return 500 ProblemDetail with a stable, generic error code
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error uri={}", request.getRequestURI(), ex);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle(ErrorCodes.UNEXPECTED_ERROR);
        problem.setDetail("An unexpected error occurred. Please try again later.");
        problem.setProperty(PROP_ERROR_CODE, ErrorCodes.UNEXPECTED_ERROR);
        problem.setProperty(PROP_RETRYABLE, false);
        return ResponseEntity.internalServerError().body(problem);
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    private ProblemDetail buildValidationProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(ErrorCodes.VALIDATION_ERROR);
        problem.setDetail("Request validation failed. Check the supplied parameters.");
        problem.setProperty(PROP_ERROR_CODE, ErrorCodes.VALIDATION_ERROR);
        problem.setProperty(PROP_RETRYABLE, false);
        return problem;
    }

    /**
     * Builds a {@link ProblemDetail} populated with the standard fields common to all
     * {@link HomeException} types. The consumer-facing message (not the technical detail) is
     * used as the RFC 7807 {@code detail} field.
     */
    private ProblemDetail buildBaseProblem(HomeException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.getStatus());
        problem.setTitle(ex.getErrorCode());
        problem.setDetail(ex.getMessage());
        problem.setProperty(PROP_ERROR_CODE, ex.getErrorCode());
        problem.setProperty(PROP_CATEGORY, ex.getCategory().name());
        problem.setProperty(PROP_RETRYABLE, ex.isRetryable());
        return problem;
    }
}
