package com.spotify.recommender.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps request-parameter validation failures ({@code @Validated} + {@code @RequestParam}
     * constraints such as {@code @Min}/{@code @Max}/{@code @Pattern}) to a 400.
     *
     * <p>Fail-safe: these are client errors (bad input), so an explicit 400 is correct.
     * Without this handler a {@link ConstraintViolationException} propagates as a 500, turning
     * a rejected-input case into a server error — the opposite of failing safe.
     *
     * @param ex the constraint violation raised by method-level validation
     * @return a 400 response describing the validation failure
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Request parameter validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "error", "validation_failed",
                "message", ex.getMessage()));
    }

    @ExceptionHandler(SpotifyRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(SpotifyRateLimitException ex) {
        log.warn("Spotify rate limit hit, retry after {}s", ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
            .body(Map.of(
                "error", "rate_limited",
                "retryAfterSeconds", ex.getRetryAfterSeconds()));
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<Map<String, Object>> handleSpotifyApiError(WebClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        if (status.is5xxServerError()) {
            log.error("Spotify API returned server error {}", status.value(), ex);
        } else if (status.value() == HttpStatus.FORBIDDEN.value() || status.value() == HttpStatus.GONE.value()) {
            // 403/410 are expected here for apps created after Nov 2024, where RecommendationService
            // falls back to the related-artist engine; only requests outside that path reach this advice.
            log.info("Spotify API returned {} (fallback-eligible)", status.value());
        } else {
            log.warn("Spotify API returned error {}", status.value());
        }
        return ResponseEntity.status(status)
            .body(Map.of(
                "error", "spotify_api_error",
                "status", status.value()));
    }
}