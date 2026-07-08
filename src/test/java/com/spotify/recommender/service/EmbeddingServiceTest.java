package com.spotify.recommender.service;

import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock private SpotifyApiService spotifyApi;
    @Mock private EmbeddingModel embeddingModel;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AppUserRepository userRepository;
    @Mock private OAuth2AuthorizedClientService authorizedClientService;

    /** Service instance with RAG enabled (non-blank key). */
    private EmbeddingService service;

    /** Service instance with RAG disabled (blank key). */
    private EmbeddingService disabledService;

    @BeforeEach
    void setUp() {
        service = new EmbeddingService(spotifyApi, embeddingModel, jdbcTemplate,
            userRepository, authorizedClientService, 100, 35, "test-api-key");
        disabledService = new EmbeddingService(spotifyApi, embeddingModel, jdbcTemplate,
            userRepository, authorizedClientService, 100, 35, "");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AppUser user(long id, String spotifyId) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setSpotifyId(spotifyId);
        return u;
    }

    private OAuth2AuthorizedClient stubToken(String spotifyId, String tokenValue) {
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        OAuth2AccessToken token = mock(OAuth2AccessToken.class);
        when(token.getTokenValue()).thenReturn(tokenValue);
        when(client.getAccessToken()).thenReturn(token);
        when(authorizedClientService.loadAuthorizedClient("spotify", spotifyId)).thenReturn(client);
        return client;
    }

    private TrackDto track(String id, String name, String artist) {
        TrackDto.ArtistRef ref = new TrackDto.ArtistRef();
        ref.setName(artist);
        TrackDto t = new TrackDto();
        t.setId(id);
        t.setName(name);
        t.setArtists(List.of(ref));
        return t;
    }

    private SpotifyPage<PlaylistDto> emptyPlaylistPage() {
        SpotifyPage<PlaylistDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        return page;
    }

    private SpotifyPage<TrackDto> emptyTopTracksPage() {
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        return page;
    }

    private EmbeddingResponse embeddingResponse(float[]... vectors) {
        List<Embedding> results = new java.util.ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            results.add(new Embedding(vectors[i], i));
        }
        return new EmbeddingResponse(results);
    }

    // ── RAG disabled: no-ops ──────────────────────────────────────────────────

    @Test
    void buildLibrary_doesNothingWhenRagDisabled() {
        disabledService.buildLibrary(user(1L, "u1"));

        verify(authorizedClientService, never()).loadAuthorizedClient(anyString(), anyString());
        verify(spotifyApi, never()).getPlaylistsWithToken(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void findRelevantTracks_returnsEmptyListWhenRagDisabled() {
        List<String> result = disabledService.findRelevantTracks(1L, "chill music", 35);

        assertThat(result).isEmpty();
        verify(embeddingModel, never()).call(any());
    }

    @Test
    void findRelevantTracks_returnsEmptyListWhenUserIdIsNull() {
        List<String> result = service.findRelevantTracks(null, "chill music", 35);

        assertThat(result).isEmpty();
        verify(embeddingModel, never()).call(any());
    }

    // ── findRelevantTracks: happy path ────────────────────────────────────────

    @Test
    void findRelevantTracks_returnsTracksFromDatabase() {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
            .thenReturn(embeddingResponse(vector));
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                anyLong(), anyString(), anyInt()))
            .thenReturn(List.of("Texas Sun by Khruangbin", "Be My Mistake by The 1975"));

        List<String> result = service.findRelevantTracks(1L, "chill music", 35);

        assertThat(result).containsExactly("Texas Sun by Khruangbin", "Be My Mistake by The 1975");
    }

    @Test
    void findRelevantTracks_returnsEmptyListWhenJdbcFails() {
        float[] vector = new float[]{0.1f, 0.2f};
        when(embeddingModel.call(any(EmbeddingRequest.class)))
            .thenReturn(embeddingResponse(vector));
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                anyLong(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("DB error"));

        List<String> result = service.findRelevantTracks(1L, "chill music", 35);

        assertThat(result).isEmpty();
    }

    @Test
    void findRelevantTracks_returnsEmptyListWhenEmbeddingReturnsNoResults() {
        when(embeddingModel.call(any(EmbeddingRequest.class)))
            .thenReturn(new EmbeddingResponse(List.of()));

        List<String> result = service.findRelevantTracks(1L, "some query", 35);

        assertThat(result).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
            anyLong(), anyString(), anyInt());
    }

    // ── hasEmbeddings ─────────────────────────────────────────────────────────

    @Test
    void hasEmbeddings_returnsFalseWhenNullUserId() {
        assertThat(service.hasEmbeddings(null)).isFalse();
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class), anyLong());
    }

    @Test
    void hasEmbeddings_returnsFalseWhenCountIsZero() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(0);

        assertThat(service.hasEmbeddings(1L)).isFalse();
    }

    @Test
    void hasEmbeddings_returnsTrueWhenCountIsPositive() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(5);

        assertThat(service.hasEmbeddings(1L)).isTrue();
    }

    @Test
    void hasEmbeddings_returnsFalseWhenJdbcFails() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L)))
            .thenThrow(new RuntimeException("DB error"));

        assertThat(service.hasEmbeddings(1L)).isFalse();
    }

    // ── buildLibrary: no token ────────────────────────────────────────────────

    @Test
    void buildLibrary_skipsWhenNoTokenAvailable() {
        AppUser user = user(1L, "u1");
        when(authorizedClientService.loadAuthorizedClient("spotify", "u1")).thenReturn(null);

        service.buildLibrary(user);

        verify(spotifyApi, never()).getPlaylistsWithToken(anyString(), anyString(), anyInt(), anyInt());
    }

    // ── buildLibrary: happy path with embedding ───────────────────────────────

    @Test
    void buildLibrary_embedsAndUpsertsTracksFromPlaylist() {
        AppUser user = user(1L, "u1");
        stubToken("u1", "tok");

        PlaylistDto playlist = new PlaylistDto();
        playlist.setId("pl1");
        playlist.setName("Chill Vibes");
        SpotifyPage<PlaylistDto> playlistPage = new SpotifyPage<>();
        playlistPage.setItems(List.of(playlist));
        when(spotifyApi.getPlaylistsWithToken("tok", "u1", 0, 50)).thenReturn(playlistPage);
        // Size 1 < PAGE (50), so pagination stops after one call.

        PlaylistTrackDto pt = new PlaylistTrackDto();
        pt.setTrack(track("t1", "Texas Sun", "Khruangbin"));
        SpotifyPage<PlaylistTrackDto> trackPage = new SpotifyPage<>();
        trackPage.setItems(List.of(pt));
        when(spotifyApi.getPlaylistTracksWithToken("tok", "pl1", 0, 100)).thenReturn(trackPage);

        for (String range : List.of("short_term", "medium_term", "long_term")) {
            when(spotifyApi.getTopTracksWithToken("tok", range, 50)).thenReturn(emptyTopTracksPage());
        }

        when(embeddingModel.call(any(EmbeddingRequest.class)))
            .thenReturn(embeddingResponse(new float[]{0.1f, 0.2f}));

        service.buildLibrary(user);

        verify(embeddingModel).call(any(EmbeddingRequest.class));
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO track_embedding"),
            any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
    }
}
