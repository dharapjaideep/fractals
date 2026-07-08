package com.spotify.recommender.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Async} and {@code @Scheduled} support for background tasks.
 *
 * <p>{@link EnableAsync} routes methods annotated with {@code @Async} onto a Spring-managed
 * thread pool rather than the calling thread — used by
 * {@link com.spotify.recommender.service.PlaceholderService#regenerateAsync} so that
 * Anthropic placeholder generation does not block the OAuth2 redirect after first login.
 *
 * <p>{@link EnableScheduling} activates the daily cron job in
 * {@link com.spotify.recommender.service.PlaceholderService#refreshAllPlaceholders}.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncSchedulingConfig {
}
