package com.spotify.recommender.exception;

public class SpotifyRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public SpotifyRateLimitException(long retryAfterSeconds) {
        super("Spotify rate limit hit. Retry after " + retryAfterSeconds + "s.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
