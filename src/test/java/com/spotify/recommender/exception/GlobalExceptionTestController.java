package com.spotify.recommender.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;

// Stub controller used only by GlobalExceptionHandlerTest to trigger each
// exception the advice handles, without coupling the test to a real controller.
@RestController
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
}