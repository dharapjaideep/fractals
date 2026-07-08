package com.spotify.recommender.service;

import com.spotify.recommender.model.dto.spotify.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single gateway for all Spotify Web API HTTP calls.
 *
 * <p>Uses a WebFlux {@link WebClient} with {@code .block()} at the service boundary so that
 * callers (servlet-layer services) remain synchronous. OAuth2 access tokens are injected
 * automatically by the {@code WebClient} bean configured in {@code WebClientConfig} — no
 * manual token handling here.
 *
 * <p>All mutating calls are public so they can be used by multiple services
 * ({@link RecommendationService}, {@link ChatService}). Read-only calls follow the same pattern.
 * Retry policy: 3 retries with 1 s back-off on 5xx only; 4xx propagates immediately.
 */
@Service
public class SpotifyApiService {

    private static final Logger log = LoggerFactory.getLogger(SpotifyApiService.class);

    private final WebClient client;

    /**
     * A plain WebClient with no OAuth2 exchange filter, used exclusively by background tasks
     * (async first-login, daily scheduled job) that run without an HTTP servlet request context.
     * Those contexts cannot use the standard {@code spotifyWebClient} because
     * {@code DefaultOAuth2AuthorizedClientManager} requires an active HTTP request to resolve
     * the user's token. Callers must supply the Bearer token explicitly.
     */
    private final WebClient backgroundClient;

    public SpotifyApiService(WebClient spotifyWebClient,
                             @Value("${spotify.api.base-url}") String baseUrl) {
        this.client = spotifyWebClient;
        this.backgroundClient = WebClient.builder()
            .baseUrl(baseUrl)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }

    // ── User ─────────────────────────────────────────────────────────────────

    /**
     * Fetches the authenticated user's Spotify profile ({@code GET /me}).
     *
     * @return the user's profile as a {@link SpotifyUserDto}
     */
    public SpotifyUserDto getProfile() {
        return client.get().uri("/me")
            .retrieve()
            .bodyToMono(SpotifyUserDto.class)
            .retryWhen(transientRetry())
            .block();
    }

    /**
     * Fetches the user's top tracks for a given time range ({@code GET /me/top/tracks}).
     *
     * @param timeRange Spotify time range: {@code short_term}, {@code medium_term}, or {@code long_term}
     * @param limit     maximum number of tracks to return (Spotify max: 50)
     * @return a page of {@link TrackDto} items
     */
    public SpotifyPage<TrackDto> getTopTracks(String timeRange, int limit) {
        SpotifyPage<TrackDto> page = client.get()
            .uri(u -> u.path("/me/top/tracks")
                .queryParam("time_range", timeRange)
                .queryParam("limit", limit)
                .build())
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<SpotifyPage<TrackDto>>() {})
            .retryWhen(transientRetry())
            .block();

        return page;
    }

    /**
     * Fetches the user's top tracks using an explicit Bearer token, for use in background tasks
     * that have no HTTP servlet request context (async first-login, daily scheduled job).
     *
     * <p>Uses {@link #backgroundClient} (no OAuth2 filter) with the token set as a per-request
     * header. The access token must be loaded by the caller from {@link
     * org.springframework.security.oauth2.client.OAuth2AuthorizedClientService}.
     *
     * @param accessToken the raw Spotify access token value (no "Bearer " prefix)
     * @param timeRange   Spotify time range: {@code short_term}, {@code medium_term}, or {@code long_term}
     * @param limit       maximum number of tracks to return (Spotify max: 50)
     * @return a page of {@link TrackDto} items
     */
    public SpotifyPage<TrackDto> getTopTracksWithToken(String accessToken, String timeRange, int limit) {
        return backgroundClient.get()
            .uri(u -> u.path("/me/top/tracks")
                .queryParam("time_range", timeRange)
                .queryParam("limit", limit)
                .build())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<SpotifyPage<TrackDto>>() {})
            .retryWhen(transientRetry())
            .block();
    }

    /**
     * Fetches the user's top artists for a given time range ({@code GET /me/top/artists}).
     *
     * @param timeRange Spotify time range: {@code short_term}, {@code medium_term}, or {@code long_term}
     * @param limit     maximum number of artists to return (Spotify max: 50)
     * @return a page of {@link ArtistDto} items
     */
    public SpotifyPage<ArtistDto> getTopArtists(String timeRange, int limit) {
        return client.get()
            .uri(u -> u.path("/me/top/artists")
                .queryParam("time_range", timeRange)
                .queryParam("limit", limit)
                .build())
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<SpotifyPage<ArtistDto>>() {})
            .retryWhen(transientRetry())
            .block();
    }

    // ── Playlists ─────────────────────────────────────────────────────────────

    /**
     * Fetches a paginated page of the authenticated user's playlists ({@code GET /me/playlists}).
     *
     * <p>Items are filtered to exclude playlists not owned by the current user (e.g. followed
     * playlists from other users). The current user's Spotify ID is resolved from the Spring
     * Security context — no extra API call required.
     *
     * @param offset zero-based index of the first playlist to return
     * @param limit  maximum number of playlists to return (Spotify max: 50)
     * @return a page of {@link PlaylistDto} items owned by the current user
     */
    public SpotifyPage<PlaylistDto> getPlaylists(int offset, int limit) {
        SpotifyPage<PlaylistDto> page = client.get()
            .uri(u -> u.path("/me/playlists")
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .build())
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<SpotifyPage<PlaylistDto>>() {})
            .retryWhen(transientRetry())
            .block();
        if (page != null && page.getItems() != null) {
            // Security: remove playlists the user follows but doesn't own so no code path
            // in Fractals can accidentally read or write to another user's playlist.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String currentUserId = auth != null ? auth.getName() : null;
            if (currentUserId != null) {
                page.getItems().removeIf(p ->
                    p.getOwner() == null || !currentUserId.equals(p.getOwner().getId()));
            }
        }
        return page;
    }

    /**
     * Fetches a paginated page of tracks in a playlist ({@code GET /playlists/{id}/tracks}).
     *
     * @param playlistId Spotify playlist ID
     * @param offset     zero-based index of the first track to return
     * @param limit      maximum number of tracks to return (Spotify max: 100)
     * @return a page of {@link PlaylistTrackDto} items
     */
    public SpotifyPage<PlaylistTrackDto> getPlaylistTracks(String playlistId, int offset, int limit) {
        SpotifyPage<PlaylistTrackDto> page = client.get()
            .uri(u -> u.path("/playlists/{id}/tracks")
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .build(playlistId))
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<SpotifyPage<PlaylistTrackDto>>() {})
            .retryWhen(transientRetry())
            .block();
        return page;
    }

    /**
     * Fetches a paginated page of the authenticated user's playlists using an explicit Bearer
     * token, for use in background tasks that have no HTTP servlet request context.
     *
     * <p>Items are filtered to exclude playlists not owned by {@code ownerSpotifyId} (e.g.
     * followed playlists), so callers never see or process playlists created by other users.
     *
     * @param accessToken    the raw Spotify access token value (no "Bearer " prefix)
     * @param ownerSpotifyId the Spotify user ID whose owned playlists to return
     * @param offset         zero-based index of the first playlist to return
     * @param limit          maximum number of playlists to return (Spotify max: 50)
     * @return a page of {@link PlaylistDto} items owned by {@code ownerSpotifyId}
     */
    public SpotifyPage<PlaylistDto> getPlaylistsWithToken(String accessToken,
            String ownerSpotifyId, int offset, int limit) {
        SpotifyPage<PlaylistDto> page = backgroundClient.get()
            .uri(u -> u.path("/me/playlists")
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .build())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<SpotifyPage<PlaylistDto>>() {})
            .retryWhen(transientRetry())
            .block();
        if (page != null && page.getItems() != null) {
            // Security: remove playlists the user follows but doesn't own.
            page.getItems().removeIf(p ->
                p.getOwner() == null || !ownerSpotifyId.equals(p.getOwner().getId()));
        }
        return page;
    }

    /**
     * Fetches a paginated page of tracks in a playlist using an explicit Bearer token, for use
     * in background tasks that have no HTTP servlet request context.
     *
     * @param accessToken the raw Spotify access token value (no "Bearer " prefix)
     * @param playlistId  Spotify playlist ID
     * @param offset      zero-based index of the first track to return
     * @param limit       maximum number of tracks to return (Spotify max: 100)
     * @return a page of {@link PlaylistTrackDto} items
     */
    public SpotifyPage<PlaylistTrackDto> getPlaylistTracksWithToken(String accessToken,
            String playlistId, int offset, int limit) {
        return backgroundClient.get()
            .uri(u -> u.path("/playlists/{id}/tracks")
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .build(playlistId))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<SpotifyPage<PlaylistTrackDto>>() {})
            .retryWhen(transientRetry())
            .block();
    }

    // ── Playlists (write) ────────────────────────────────────────────────────

    /**
     * Returns the ID of the user's playlist named {@code playlistName}, creating it if absent.
     *
     * <p>Searches only the first page of playlists (up to 50). Users with more than 50 playlists
     * may see a duplicate created — this is a known limitation acceptable for the current scale.
     *
     * @param playlistName exact playlist name to find or create
     * @return the Spotify playlist ID
     */
    public String getOrCreatePlaylist(String playlistName) {
        List<PlaylistDto> playlists = getPlaylists(0, 50).getItems();
        for (PlaylistDto playlist : playlists) {
            if (playlistName.equals(playlist.getName())) {
                return playlist.getId();
            }
        }

        String userId = getProfile().getId();
        PlaylistDto created = client.post()
            .uri("/users/{userId}/playlists", userId)
            .bodyValue(Map.of(
                "name", playlistName,
                "public", false,
                "description", "Created by Fractals recommendation engine"))
            .retrieve()
            .bodyToMono(PlaylistDto.class)
            .retryWhen(transientRetry())
            .block();
        return created.getId();
    }

    /**
     * Returns the set of track URIs present in the first page (up to 50) of a playlist.
     * Used for lightweight deduplication checks before adding tracks.
     *
     * @param playlistId Spotify playlist ID
     * @return set of Spotify track URIs; empty if the playlist is empty or the call fails
     */
    public Set<String> getPlaylistTrackUris(String playlistId) {
        SpotifyPage<PlaylistTrackDto> page = client.get()
            .uri(u -> u.path("/playlists/{id}/tracks")
                .queryParam("fields", "items(track(uri))")
                .queryParam("limit", 50)
                .build(playlistId))
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<SpotifyPage<PlaylistTrackDto>>() {})
            .retryWhen(transientRetry())
            .block();

        Set<String> uris = new HashSet<>();
        if (page != null && page.getItems() != null) {
            for (PlaylistTrackDto item : page.getItems()) {
                if (item.getTrack() != null && item.getTrack().getUri() != null) {
                    uris.add(item.getTrack().getUri());
                }
            }
        }
        return uris;
    }

    /**
     * Appends tracks to a playlist ({@code POST /playlists/{id}/tracks}).
     *
     * @param playlistId Spotify playlist ID
     * @param trackUris  list of Spotify track URIs to add (e.g. {@code spotify:track:...})
     */
    public void addTracksToPlaylist(String playlistId, List<String> trackUris) {
        client.post()
            .uri("/playlists/{id}/tracks", playlistId)
            .bodyValue(Map.of("uris", trackUris))
            .retrieve()
            .bodyToMono(Void.class)
            .retryWhen(transientRetry())
            .block();
    }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Searches Spotify for a track by artist and track name, returning the first result.
     *
     * <p>Returns {@link Optional#empty()} — rather than throwing — when no results are found
     * or the search request fails. Callers can skip unresolvable suggestions without
     * interrupting the overall recommendation flow.
     *
     * @param artist    the artist name to search for
     * @param trackName the track name to search for
     * @return the first matching {@link TrackDto}, or empty if not found
     */
    public Optional<TrackDto> resolveToSpotifyTrack(String artist, String trackName) {
        try {
            SpotifySearchResponse response = client.get()
                .uri(u -> u.path("/search")
                    .queryParam("q", "track:" + trackName + " artist:" + artist)
                    .queryParam("type", "track")
                    .queryParam("limit", 1)
                    .build())
                .retrieve()
                .bodyToMono(SpotifySearchResponse.class)
                .retryWhen(transientRetry())
                .block();

            List<TrackDto> items = response != null && response.getTracks() != null
                ? response.getTracks().getItems()
                : null;
            if (items == null || items.isEmpty()) {
                log.warn("No Spotify search results for track='{}' artist='{}'", trackName, artist);
                return Optional.empty();
            }
            return Optional.of(items.get(0));
        } catch (RuntimeException e) {
            // Catches both immediate 4xx WebClientResponseExceptions and the
            // Exceptions.RetryExhaustedException thrown after retryWhen() exhausts 5xx retries.
            log.warn("Spotify search failed for track='{}' artist='{}': {}",
                trackName, artist, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Retry policy ──────────────────────────────────────────────────────────

    // Retries up to 3 times on 5xx (server errors) with exponential backoff.
    // 4xx errors (including 403 deprecated endpoints) propagate immediately.
    private Retry transientRetry() {
        return Retry.backoff(3, Duration.ofSeconds(1))
            .filter(ex -> ex instanceof WebClientResponseException wcre
                && wcre.getStatusCode().is5xxServerError());
    }
}