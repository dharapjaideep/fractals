package com.spotify.recommender.service;

import com.spotify.recommender.exception.SpotifyRateLimitException;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.SpotifyUserDto;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotifyApiServiceTest {

    private MockWebServer mockWebServer;
    private SpotifyApiService service;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Plain WebClient (no OAuth2 filter) — focuses on HTTP/JSON behaviour
        WebClient webClient = WebClient.builder()
            .baseUrl(mockWebServer.url("/").toString())
            .defaultStatusHandler(
                status -> status.value() == 429,
                response -> {
                    String retryAfter = response.headers().asHttpHeaders().getFirst("Retry-After");
                    long seconds = retryAfter != null ? Long.parseLong(retryAfter) : 1L;
                    return Mono.error(new SpotifyRateLimitException(seconds));
                })
            .build();

        service = new SpotifyApiService(webClient, mockWebServer.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── getProfile ────────────────────────────────────────────────────────────

    @Test
    void getProfile_deserializesDisplayName() {
        mockWebServer.enqueue(new MockResponse()
            .setBody("""
                {"id":"user1","display_name":"Alice","email":"alice@example.com","product":"premium"}
                """)
            .addHeader("Content-Type", "application/json"));

        SpotifyUserDto result = service.getProfile();

        assertThat(result.getId()).isEqualTo("user1");
        assertThat(result.getDisplayName()).isEqualTo("Alice");
        assertThat(result.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void getProfile_callsCorrectPath() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"id\":\"u\"}")
            .addHeader("Content-Type", "application/json"));

        service.getProfile();

        RecordedRequest req = mockWebServer.takeRequest();
        assertThat(req.getPath()).isEqualTo("/me");
        assertThat(req.getMethod()).isEqualTo("GET");
    }

    // ── getTopTracks ──────────────────────────────────────────────────────────

    @Test
    void getTopTracks_deserializesPage() {
        mockWebServer.enqueue(new MockResponse()
            .setBody("""
                {
                  "items": [
                    {"id":"t1","name":"Track One","popularity":80,"artists":[]},
                    {"id":"t2","name":"Track Two","popularity":70,"artists":[]}
                  ],
                  "total": 2,
                  "limit": 20,
                  "offset": 0
                }
                """)
            .addHeader("Content-Type", "application/json"));

        SpotifyPage<TrackDto> page = service.getTopTracks("medium_term", 20);

        assertThat(page.getItems()).hasSize(2);
        assertThat(page.getItems().get(0).getId()).isEqualTo("t1");
        assertThat(page.getTotal()).isEqualTo(2);
    }

    @Test
    void getTopTracks_includesTimeRangeAndLimitParams() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"items\":[],\"total\":0,\"limit\":10,\"offset\":0}")
            .addHeader("Content-Type", "application/json"));

        service.getTopTracks("short_term", 10);

        RecordedRequest req = mockWebServer.takeRequest();
        assertThat(req.getPath()).contains("time_range=short_term");
        assertThat(req.getPath()).contains("limit=10");
    }

    // ── Rate limiting ──────────────────────────────────────────────────────────

    @Test
    void getProfile_throws_SpotifyRateLimitException_on429() {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(429)
            .addHeader("Retry-After", "3")
            .setBody("rate limited"));

        assertThatThrownBy(() -> service.getProfile())
            .isInstanceOf(SpotifyRateLimitException.class)
            .satisfies(ex ->
                assertThat(((SpotifyRateLimitException) ex).getRetryAfterSeconds()).isEqualTo(3));
    }

    @Test
    void getProfile_throws_WebClientResponseException_on403() {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(403)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"error\":{\"status\":403,\"message\":\"Forbidden\"}}"));

        assertThatThrownBy(() -> service.getProfile())
            .isInstanceOf(WebClientResponseException.Forbidden.class);
    }

    // ── resolveToSpotifyTrack ────────────────────────────────────────────────

    @Test
    void resolveToSpotifyTrack_returnsPopulatedTrackDto_onMatch() {
        mockWebServer.enqueue(new MockResponse()
            .setBody("""
                {
                  "tracks": {
                    "items": [
                      {"id":"t1","uri":"spotify:track:t1","name":"Track One","popularity":80,
                       "artists":[{"id":"a1","name":"Artist One"}]}
                    ]
                  }
                }
                """)
            .addHeader("Content-Type", "application/json"));

        Optional<TrackDto> result = service.resolveToSpotifyTrack("Artist One", "Track One");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("t1");
        assertThat(result.get().getUri()).isEqualTo("spotify:track:t1");
        assertThat(result.get().getPopularity()).isEqualTo(80);
    }

    @Test
    void resolveToSpotifyTrack_returnsEmpty_whenNoResults() {
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"tracks\":{\"items\":[]}}")
            .addHeader("Content-Type", "application/json"));

        Optional<TrackDto> result = service.resolveToSpotifyTrack("Unknown Artist", "Unknown Track");

        assertThat(result).isEmpty();
    }

    @Test
    void resolveToSpotifyTrack_returnsEmpty_onApiError() {
        // transientRetry() retries 5xx 3 times (4 total attempts) before giving up.
        for (int i = 0; i < 4; i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("server error"));
        }

        Optional<TrackDto> result = service.resolveToSpotifyTrack("Artist One", "Track One");

        assertThat(result).isEmpty();
    }

    // ── getOrCreatePlaylist ──────────────────────────────────────────────────

    @Test
    void getOrCreatePlaylist_findsExistingPlaylist_returnsId() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setBody("""
                {"items":[
                  {"id":"p1","name":"Fractals-CHILL"},
                  {"id":"p2","name":"Other Playlist"}
                ],"total":2,"limit":50,"offset":0}
                """)
            .addHeader("Content-Type", "application/json"));

        String playlistId = service.getOrCreatePlaylist("Fractals-CHILL");

        assertThat(playlistId).isEqualTo("p1");
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void getOrCreatePlaylist_createsNewPlaylist_whenNotFound() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"items\":[],\"total\":0,\"limit\":50,\"offset\":0}")
            .addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"id\":\"user1\"}")
            .addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"id\":\"p-new\",\"name\":\"Fractals-CHILL\"}")
            .addHeader("Content-Type", "application/json"));

        String playlistId = service.getOrCreatePlaylist("Fractals-CHILL");

        assertThat(playlistId).isEqualTo("p-new");

        mockWebServer.takeRequest(); // GET /me/playlists
        mockWebServer.takeRequest(); // GET /me
        RecordedRequest createReq = mockWebServer.takeRequest();
        assertThat(createReq.getMethod()).isEqualTo("POST");
        assertThat(createReq.getPath()).isEqualTo("/users/user1/playlists");
        String body = createReq.getBody().readUtf8();
        assertThat(body).contains("\"name\":\"Fractals-CHILL\"");
        assertThat(body).contains("\"public\":false");
    }

    // ── getPlaylistTrackUris ─────────────────────────────────────────────────

    @Test
    void getPlaylistTrackUris_returnsUrisFromItems() {
        mockWebServer.enqueue(new MockResponse()
            .setBody("""
                {"items":[
                  {"track":{"uri":"spotify:track:t1"}},
                  {"track":{"uri":"spotify:track:t2"}}
                ]}
                """)
            .addHeader("Content-Type", "application/json"));

        var uris = service.getPlaylistTrackUris("playlist1");

        assertThat(uris).containsExactlyInAnyOrder("spotify:track:t1", "spotify:track:t2");
    }
}
