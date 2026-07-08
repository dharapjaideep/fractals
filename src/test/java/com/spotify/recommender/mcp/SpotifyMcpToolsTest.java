package com.spotify.recommender.mcp;

import com.spotify.recommender.exception.SpotifyRateLimitException;
import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.SpotifyUserDto;
import com.spotify.recommender.service.RecommendationService;
import com.spotify.recommender.service.SpotifyApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpotifyMcpToolsTest {

    @Mock
    private SpotifyApiService spotifyApi;

    @Mock
    private RecommendationService recommendationService;

    private SpotifyMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new SpotifyMcpTools(spotifyApi, recommendationService);
    }

    // ── validateLimit ─────────────────────────────────────────────────────────

    @Test
    void getTopTracks_limitBelowMin_throws() {
        assertThatThrownBy(() -> tools.getTopTracks(SpotifyMcpTools.TimeRange.short_term, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getTopTracks_limitAboveMax_throws() {
        assertThatThrownBy(() -> tools.getTopTracks(SpotifyMcpTools.TimeRange.short_term, 21))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getRecommendations_limitBelowMin_throws() {
        assertThatThrownBy(() -> tools.getRecommendations(0, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getRecommendations_limitAboveMax_throws() {
        assertThatThrownBy(() -> tools.getRecommendations(21, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── energyBoost range ────────────────────────────────────────────────────

    @Test
    void getRecommendations_energyBoostBelowMin_throws() {
        assertThatThrownBy(() -> tools.getRecommendations(5, null, -1.5f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getRecommendations_energyBoostAboveMax_throws() {
        assertThatThrownBy(() -> tools.getRecommendations(5, null, 1.5f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── sanitizedError ───────────────────────────────────────────────────────

    @Test
    void getTopTracks_rateLimitException_sanitizesMessage_noUpstreamLeak() {
        when(spotifyApi.getTopTracks(anyString(), anyInt())).thenThrow(new SpotifyRateLimitException(5));

        Throwable thrown = catchThrowable(() -> tools.getTopTracks(SpotifyMcpTools.TimeRange.short_term, 5));

        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(thrown.getMessage())
            .isEqualTo("Spotify rate limit hit while fetching top tracks; retry after 5s.")
            .doesNotContain("http");
    }

    @Test
    void getTopTracks_apiError_sanitizesMessage_noUpstreamLeak() {
        // Body simulates upstream content (secret-looking value + a URL) that must never
        // reach the MCP client, per the report's "trust the decompile" finding.
        byte[] upstreamBody = "{\"secret\":\"secret-upstream-detail\",\"url\":\"https://api.spotify.com/internal\"}"
            .getBytes();
        WebClientResponseException upstream =
            WebClientResponseException.create(503, "Service Unavailable", new HttpHeaders(), upstreamBody, null);
        when(spotifyApi.getTopTracks(anyString(), anyInt())).thenThrow(upstream);

        Throwable thrown = catchThrowable(() -> tools.getTopTracks(SpotifyMcpTools.TimeRange.short_term, 5));

        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(thrown.getMessage())
            .isEqualTo("Spotify API error (503) while fetching top tracks.")
            .doesNotContain("secret-upstream-detail")
            .doesNotContain("https://api.spotify.com");
    }

    // ── Null-safe DTO mapping ────────────────────────────────────────────────

    @Test
    void getPlaylists_nullTracksAndIsPublic_mapsSafely() {
        PlaylistDto dto = new PlaylistDto();
        dto.setId("1");
        dto.setName("Chill");
        dto.setDescription("desc");
        dto.setCollaborative(false);
        dto.setIsPublic(null);
        dto.setTracks(null);

        SpotifyPage<PlaylistDto> page = new SpotifyPage<>();
        page.setItems(List.of(dto));
        when(spotifyApi.getPlaylists(0, 20)).thenReturn(page);

        List<SpotifyMcpTools.McpPlaylist> result = tools.getPlaylists();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).trackCount()).isZero();
        assertThat(result.get(0).isPublic()).isFalse();
    }

    @Test
    void getUserProfile_nullFollowers_mapsSafely() {
        SpotifyUserDto dto = new SpotifyUserDto();
        dto.setDisplayName("Jay");
        dto.setCountry("US");
        dto.setProduct("premium");
        dto.setFollowers(null);
        when(spotifyApi.getProfile()).thenReturn(dto);

        SpotifyMcpTools.McpUserProfile result = tools.getUserProfile();

        assertThat(result.followers()).isZero();
    }

    // ── Read-only tool surface (defence in depth for the SecurityConfig CSRF
    //    exemption on /mcp/** — any future write tool must fail this test) ────

    // Note: this test catches the most common write patterns
    // (void return, non-get naming) but cannot enforce read-only
    // intent exhaustively via reflection alone. The primary
    // guardrails are: (1) the CSRF exemption comment in
    // SecurityConfig.java, (2) code review, and (3) this test
    // as an early warning for obvious violations.
    // See: SecurityConfig.java CSRF exemption for /mcp/**
    @Test
    void allToolMethods_areReadOnly() {
        List<Method> toolMethods = Arrays.stream(SpotifyMcpTools.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Tool.class))
            .toList();

        // Guard against vacuous pass
        assertThat(toolMethods).isNotEmpty();

        for (Method method : toolMethods) {
            String toolName = method.getAnnotation(Tool.class).name();

            // Check 1: no void return type — write operations typically return void
            assertThat(method.getReturnType())
                .as("Tool '%s' must not return void — " +
                    "MCP tools must be read-only per SecurityConfig.java CSRF exemption", toolName)
                .isNotEqualTo(Void.TYPE);

            // Check 2: naming convention as an additional signal
            assertThat(method.getName())
                .as("Tool '%s' method name should start with 'get' for read-only intent", toolName)
                .startsWith("get");
        }
    }
}