package com.spotify.recommender.exception;

import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;

// Stub controller used only by GlobalExceptionHandlerTest to trigger each
// exception the advice handles, without coupling the test to a real controller.
@RestController
@Validated
class GlobalExceptionTestController {

    @GetMapping("/test/rate-limit")
    public void throwRateLimit() {
        throw new SpotifyRateLimitException(5);
    }

    @GetMapping("/test/api-error")
    public void throwApiError() {
        throw WebClientResponseException.create(403, "Forbidden", new HttpHeaders(), new byte[0], null);
    }

    @GetMapping("/test/api-error-5xx")
    public void throwApiError5xx() {
        throw WebClientResponseException.create(503, "Service Unavailable", new HttpHeaders(), new byte[0], null);
    }

    @GetMapping("/test/constraint-violation")
    public void throwConstraintViolation(@RequestParam @Min(1) int value) {
        // unreachable when value satisfies @Min(1); AOP validation throws before this runs
    }
}