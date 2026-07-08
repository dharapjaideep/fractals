package com.spotify.recommender.mcp;

import com.spotify.recommender.exception.SpotifyRateLimitException;
import com.spotify.recommender.model.dto.recommendation.MoodTarget;
import com.spotify.recommender.model.dto.recommendation.RecommendationRequest;
import com.spotify.recommender.model.dto.recommendation.RecommendationResponse;
import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.SpotifyUserDto;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.service.RecommendationService;
import com.spotify.recommender.service.SpotifyApiService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP tool surface over the existing Spotify integration.
 *
 * <p>Pure delegation to {@link SpotifyApiService} and {@link RecommendationService}, per
 * CLAUDE.md's architecture rule that all Spotify API calls must go through the service layer.
 *
 * <p>Every {@code @Tool} method maps service DTOs onto the local sanitized record types defined
 * at the bottom of this class, so {@code AppUser} and OAuth-internal fields can never leak into
 * an MCP response even if a service DTO gains new fields later. Spotify's own public catalog
 * IDs and URIs are preserved in the output.
 *
 * <p><strong>Security note:</strong> all tools exposed here must remain read-only. The MCP
 * endpoint ({@code /mcp/**}) is exempted from CSRF protection in {@link SecurityConfig} because
 * MCP clients are not browsers and cannot perform the cookie double-submit CSRF handshake. A
 * write tool added here would be callable via a victim's session cookie without CSRF validation —
 * see the {@code SecurityConfig} comment for full reasoning.
 */
@Component
public class SpotifyMcpTools {

    private final SpotifyApiService spotifyApi;
    private final RecommendationService recommendationService;

    public SpotifyMcpTools(SpotifyApiService spotifyApi, RecommendationService recommendationService) {
        this.spotifyApi = spotifyApi;
        this.recommendationService = recommendationService;
    }

    /**
     * Registers all {@code @Tool}-annotated methods on this bean as Spring AI tool callbacks,
     * making them available to the MCP server at {@code /mcp}.
     *
     * @return a {@link ToolCallbackProvider} backed by this component's tool methods
     */
    @Bean
    public ToolCallbackProvider spotifyMcpToolCallbacks() {
        return MethodToolCallbackProvider.builder()
            .toolObjects(this)
            .build();
    }

    /**
     * Represents the Spotify {@code time_range} query parameter for top-tracks and top-artists requests.
     *
     * <p>Lowercase names are deliberate — they match Spotify's literal query values exactly,
     * so no separate mapping is needed when calling the API.
     */
    public enum TimeRange { short_term, medium_term, long_term }

    /**
     * Returns the current user's most-played tracks on Spotify for a given time window.
     *
     * @param timeRange the time window: {@code short_term} ≈ last 4 weeks,
     *                  {@code medium_term} ≈ last 6 months, {@code long_term} = all time
     * @param limit     number of tracks to return, 1–20
     * @return a list of {@link McpTrack} records with track name, artists, album, popularity,
     *         Spotify ID, and URI
     * @throws IllegalArgumentException if {@code limit} is outside the 1–20 range
     */
    @Tool(name = "get_top_tracks", description = """
        Returns the current user's most-played tracks on Spotify for a given time window: \
        short_term = last ~4 weeks, medium_term = last ~6 months, long_term = all time. Use \
        this to answer questions about what the user has been listening to, or as input for \
        further analysis (e.g. picking seed tracks). Each result includes track name, artists, \
        album, popularity (0-100), and Spotify track ID/URI.""")
    public List<McpTrack> getTopTracks(
            @ToolParam(description = "Time window: short_term, medium_term, or long_term.")
            TimeRange timeRange,
            @ToolParam(description = "Number of tracks to return, 1-20.")
            int limit) {
        validateLimit(limit);
        try {
            return spotifyApi.getTopTracks(timeRange.name(), limit).getItems().stream()
                .map(SpotifyMcpTools::toMcpTrack)
                .collect(Collectors.toList());
        } catch (WebClientResponseException | SpotifyRateLimitException e) {
            throw sanitizedError("top tracks", e);
        }
    }

    /**
     * Generates personalized track recommendations for the current user.
     *
     * <p>Uses the Anthropic-based recommendation engine which fetches the user's top tracks as
     * context and asks Claude to suggest discovery tracks. Optional mood and energy bias parameters
     * are forwarded to {@link RecommendationService}.
     *
     * @param limit       number of tracks to return, 1–20
     * @param moodTarget  optional mood to bias results toward (HAPPY, SAD, ENERGETIC, CHILL,
     *                    FOCUSED, WORKOUT); {@code null} applies no mood bias
     * @param energyBoost optional direct energy adjustment from −1.0 to 1.0; {@code null} applies none
     * @return a list of {@link McpRecommendation} records, each containing a track and similarity score
     * @throws IllegalArgumentException if {@code limit} is outside 1–20 or {@code energyBoost}
     *                                  is outside −1.0–1.0
     */
    @Tool(name = "get_recommendations", description = """
        Generates personalized track recommendations for the current user. Uses Spotify's \
        recommendation engine re-ranked by audio-feature similarity to the user's taste profile \
        when available, automatically falling back to a related-artist + genre/popularity model \
        for Spotify apps that lost recommendation-API access after Nov 2024 — the caller does \
        not need to know which path ran. Optionally bias the result with moodTarget (HAPPY, SAD, \
        ENERGETIC, CHILL, FOCUSED, WORKOUT — each nudges valence/energy/danceability toward \
        that mood) and/or energyBoost (a direct -1.0 to 1.0 adjustment to target energy). Use this \
        when the user asks for music suggestions, a playlist idea, or 'something to match my mood'. \
        Each result includes track name, artists, album, Spotify track ID/URI, and a similarity score.""")
    public List<McpRecommendation> getRecommendations(
            @ToolParam(description = "Number of tracks to return, 1-20.")
            int limit,
            @ToolParam(required = false, description =
                "Optional mood to bias recommendations toward: HAPPY, SAD, ENERGETIC, CHILL, FOCUSED, or WORKOUT.")
            MoodTarget moodTarget,
            @ToolParam(required = false, description =
                "Optional direct adjustment to target energy, from -1.0 to 1.0.")
            Float energyBoost) {
        validateLimit(limit);
        if (energyBoost != null && (energyBoost < -1.0f || energyBoost > 1.0f)) {
            throw new IllegalArgumentException("energyBoost must be between -1.0 and 1.0");
        }
        RecommendationRequest request = new RecommendationRequest();
        request.setLimit(limit);
        request.setMoodTarget(moodTarget);
        request.setEnergyBoost(energyBoost);
        try {
            RecommendationResponse response = recommendationService.recommend(request);
            return response.getTracks().stream()
                .map(SpotifyMcpTools::toMcpRecommendation)
                .collect(Collectors.toList());
        } catch (WebClientResponseException | SpotifyRateLimitException e) {
            throw sanitizedError("recommendations", e);
        }
    }

    /**
     * Returns the current user's first 20 Spotify playlists.
     *
     * @return a list of {@link McpPlaylist} records containing name, description, track count,
     *         public/collaborative flags, and Spotify playlist ID
     */
    @Tool(name = "get_playlists", description = """
        Returns the current user's first 20 Spotify playlists (name, description, track count, \
        public/collaborative flags, Spotify playlist ID). Use this to let the user reference one \
        of their existing playlists, or to understand their library structure before recommending \
        music.""")
    public List<McpPlaylist> getPlaylists() {
        try {
            return spotifyApi.getPlaylists(0, 20).getItems().stream()
                .map(SpotifyMcpTools::toMcpPlaylist)
                .collect(Collectors.toList());
        } catch (WebClientResponseException | SpotifyRateLimitException e) {
            throw sanitizedError("playlists", e);
        }
    }

    /**
     * Returns the current user's basic Spotify profile.
     *
     * <p>The response is deliberately limited to non-sensitive data (display name, country,
     * subscription tier, follower count). Email and account-management data are excluded so that
     * MCP clients receive only the minimum information needed for personalization context.
     *
     * @return a {@link McpUserProfile} with display name, country, product tier, and follower count
     */
    @Tool(name = "get_user_profile", description = """
        Returns the current user's basic Spotify profile: display name, country, subscription \
        product tier (free/premium), and follower count. Use this for personalization context \
        (e.g. greeting the user by name) — it does not return email or any account-management data.""")
    public McpUserProfile getUserProfile() {
        try {
            return toMcpUserProfile(spotifyApi.getProfile());
        } catch (WebClientResponseException | SpotifyRateLimitException e) {
            throw sanitizedError("user profile", e);
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
    }

    // ── Fail-safe error mapping ──────────────────────────────────────────────
    // Never let a raw WebClientResponseException (which may echo back response
    // body content) or rate-limit internals reach the MCP client unsanitized.
    // Spring AI's tool layer (verified by decompiling McpToolUtils) catches any
    // exception thrown here and returns only its getMessage() as CallToolResult
    // text with isError=true — so the message built below is exactly what the
    // MCP client sees, and nothing more.
    private static RuntimeException sanitizedError(String what, RuntimeException e) {
        if (e instanceof SpotifyRateLimitException rle) {
            return new RuntimeException(
                "Spotify rate limit hit while fetching " + what + "; retry after "
                    + rle.getRetryAfterSeconds() + "s.");
        }
        WebClientResponseException wcre = (WebClientResponseException) e;
        return new RuntimeException(
            "Spotify API error (" + wcre.getStatusCode().value() + ") while fetching " + what + ".");
    }

    // ── DTO -> sanitized MCP record mapping ───────────────────────────────────

    private static McpTrack toMcpTrack(TrackDto t) {
        return new McpTrack(
            t.getId(),
            t.getUri(),
            t.getName(),
            t.getArtists().stream().map(TrackDto.ArtistRef::getName).collect(Collectors.toList()),
            t.getAlbum() != null ? t.getAlbum().getName() : null,
            t.getPopularity());
    }

    private static McpRecommendation toMcpRecommendation(RecommendationResponse.RankedTrack ranked) {
        return new McpRecommendation(toMcpTrack(ranked.getTrack()), ranked.getScore());
    }

    private static McpPlaylist toMcpPlaylist(PlaylistDto p) {
        return new McpPlaylist(
            p.getId(),
            p.getName(),
            p.getDescription(),
            p.getTracks() != null ? p.getTracks().getTotal() : 0,
            Boolean.TRUE.equals(p.getIsPublic()),
            p.isCollaborative());
    }

    private static McpUserProfile toMcpUserProfile(SpotifyUserDto u) {
        return new McpUserProfile(
            u.getDisplayName(),
            u.getCountry(),
            u.getProduct(),
            u.getFollowers() != null ? u.getFollowers().getTotal() : 0);
    }

    // ── Sanitized response shapes returned to MCP clients ─────────────────────

    /**
     * Sanitized track representation returned to MCP clients.
     * Contains only public Spotify catalog data — no internal application state.
     *
     * @param id         Spotify track ID
     * @param uri        Spotify track URI (e.g. {@code spotify:track:...})
     * @param name       track title
     * @param artists    list of artist names
     * @param album      album name, or {@code null} if unavailable
     * @param popularity Spotify popularity score, 0–100
     */
    public record McpTrack(String id, String uri, String name, List<String> artists, String album, int popularity) {}

    /**
     * A recommended track paired with its similarity score.
     *
     * @param track  the resolved Spotify track
     * @param score  similarity score in the range 0.0–1.0 (higher = closer match to user taste)
     */
    public record McpRecommendation(McpTrack track, double score) {}

    /**
     * Sanitized playlist metadata returned to MCP clients.
     *
     * @param id            Spotify playlist ID
     * @param name          playlist name
     * @param description   playlist description, may be {@code null} or blank
     * @param trackCount    total number of tracks in the playlist
     * @param isPublic      whether the playlist is publicly visible on Spotify
     * @param collaborative whether other users can add tracks to the playlist
     */
    public record McpPlaylist(String id, String name, String description, int trackCount,
                               boolean isPublic, boolean collaborative) {}

    /**
     * Sanitized user profile returned to MCP clients.
     * Email and account-management fields are intentionally excluded (least-privilege principle).
     *
     * @param displayName the user's Spotify display name
     * @param country     two-letter ISO 3166-1 alpha-2 country code of the user's account
     * @param product     Spotify subscription tier, e.g. {@code "premium"} or {@code "free"}
     * @param followers   total number of Spotify followers
     */
    public record McpUserProfile(String displayName, String country, String product, int followers) {}
}
