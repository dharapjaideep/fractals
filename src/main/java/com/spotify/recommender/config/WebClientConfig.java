package com.spotify.recommender.config;

import com.spotify.recommender.exception.SpotifyRateLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Spring configuration for the Spotify API {@link WebClient} and its OAuth2 token-injection pipeline.
 *
 * <p>Wires together three collaborating beans:
 * <ul>
 *   <li>{@link JdbcOAuth2AuthorizedClientService} — persists and retrieves OAuth2 tokens from the
 *       {@code oauth2_authorized_client} database table, keeping them durable across server restarts.</li>
 *   <li>{@link DefaultOAuth2AuthorizedClientManager} — drives automatic token refresh on expiry.</li>
 *   <li>A {@link WebClient} with a {@link ServletOAuth2AuthorizedClientExchangeFilterFunction}
 *       that attaches the current user's Spotify Bearer token to every outbound request.</li>
 * </ul>
 *
 * <p>All Spotify API calls go through the single {@code spotifyWebClient} bean. Changing token
 * injection behavior here affects every call in {@link com.spotify.recommender.service.SpotifyApiService}.
 * This file is listed in CLAUDE.md as requiring human review before changes.
 */
@Configuration
public class WebClientConfig {

    @Value("${spotify.api.base-url}")
    private String baseUrl;

    /**
     * Persists OAuth2 authorized clients (access and refresh tokens) in the database.
     *
     * <p>Tokens live in the {@code oauth2_authorized_client} table (schema in {@code schema.sql})
     * rather than in the HTTP session, keeping them durable across server restarts. This is the
     * architectural decision that ensures tokens are never stored on {@code AppUser} or any other
     * entity class — the JDBC service owns token storage exclusively.
     *
     * @param jdbcTemplate                 used to read/write the {@code oauth2_authorized_client} table
     * @param clientRegistrationRepository provides the registered Spotify client configuration
     * @return a {@link JdbcOAuth2AuthorizedClientService} backed by the application's datasource
     */
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            JdbcTemplate jdbcTemplate,
            ClientRegistrationRepository clientRegistrationRepository) {
        return new JdbcOAuth2AuthorizedClientService(jdbcTemplate, clientRegistrationRepository);
    }

    /**
     * Bridges the JDBC-backed {@link OAuth2AuthorizedClientService} into the web layer so that
     * token lookups by {@link WebClient} can resolve the current authenticated principal.
     *
     * <p>Authenticated-user lookups go through the JDBC service; anonymous users fall back to the
     * HTTP session. In practice all API paths require authentication, so the anonymous branch
     * is never exercised in production.
     *
     * @param authorizedClientService the JDBC-backed client service
     * @return an {@link OAuth2AuthorizedClientRepository} suitable for use in a servlet context
     */
    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository(
            OAuth2AuthorizedClientService authorizedClientService) {
        return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClientService);
    }

    /**
     * Configures the OAuth2 client manager with support for the Authorization Code and Refresh
     * Token grant types, enabling automatic token refresh when a stored access token expires.
     *
     * @param registrationRepository     the registered Spotify OAuth2 client configuration
     * @param authorizedClientRepository the repository used to load and save authorized clients
     * @return a configured {@link OAuth2AuthorizedClientManager}
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
            .authorizationCode()
            .refreshToken()
            .build();
        DefaultOAuth2AuthorizedClientManager manager = new DefaultOAuth2AuthorizedClientManager(
            registrationRepository, authorizedClientRepository);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /**
     * Builds the shared {@link WebClient} used for all Spotify API calls.
     *
     * <p>{@link ServletOAuth2AuthorizedClientExchangeFilterFunction} with
     * {@code setDefaultClientRegistrationId("spotify")} attaches the current user's Spotify
     * Bearer token to every request automatically — no per-call attribute setup is needed in
     * {@link com.spotify.recommender.service.SpotifyApiService}.
     *
     * <p>The 10 MB in-memory buffer cap overrides WebClient's default 256 KB to accommodate
     * large Spotify playlist-track responses that would otherwise throw
     * {@code DataBufferLimitException} on playlists with many tracks.
     *
     * <p>HTTP 429 responses from Spotify are converted to
     * {@link com.spotify.recommender.exception.SpotifyRateLimitException} carrying the
     * {@code Retry-After} header value, so callers can surface the retry window to the user
     * rather than receiving a generic error.
     *
     * @param authorizedClientManager the manager that supplies and refreshes OAuth2 tokens
     * @return a configured {@link WebClient} with Spotify base URL and token injection
     */
    @Bean
    public WebClient spotifyWebClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        ServletOAuth2AuthorizedClientExchangeFilterFunction filter =
            new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        // Attach Spotify tokens to every request without explicit per-call attributes
        filter.setDefaultClientRegistrationId("spotify");

        return WebClient.builder()
            .baseUrl(baseUrl)
            .apply(filter.oauth2Configuration())
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(10 * 1024 * 1024)) // 10MB
            .defaultStatusHandler(
                status -> status.value() == 429,
                response -> {
                    String retryAfter = response.headers().asHttpHeaders().getFirst("Retry-After");
                    long seconds = retryAfter != null ? Long.parseLong(retryAfter) : 1L;
                    return Mono.error(new SpotifyRateLimitException(seconds));
                })
            .build();
    }
}
