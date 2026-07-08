package com.spotify.recommender.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotify.recommender.model.dto.recommendation.RecommendationRequest;
import com.spotify.recommender.model.dto.recommendation.RecommendationResponse;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.service.RecommendationService.PlaylistSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct unit tests for the Anthropic-based recommendation engine. The constructor is fully
 * DI-friendly, so {@link AnthropicClient} and {@link SpotifyApiService} are mocked and a real
 * {@link ObjectMapper} is used (parseSuggestions exercises Jackson directly).
 *
 * Coverage targets (per the 2026-06-24 review HIGH finding):
 *  - stripMarkdownFence(): fenced and unfenced input
 *  - parseSuggestions(): valid array, entries missing fields, non-JSON
 *  - resolveTracks(): limit cap, skip-unresolved
 *  - recommend(): three fail-safe paths (empty top tracks, Anthropic exception, parse failure)
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private SpotifyApiService spotifyApi;

    // Deep stubs so anthropicClient.messages().create(...) can be stubbed without referencing
    // the SDK's internal MessageService type directly.
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private AnthropicClient anthropicClient;

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(
            spotifyApi, anthropicClient, new ObjectMapper(), "claude-sonnet-4-6", 1000L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Builds a SpotifyPage carrying the given top tracks. */
    private SpotifyPage<TrackDto> page(TrackDto... tracks) {
        SpotifyPage<TrackDto> p = new SpotifyPage<>();
        p.setItems(List.of(tracks));
        return p;
    }

    private TrackDto track(String id, String name) {
        TrackDto t = new TrackDto();
        t.setId(id);
        t.setName(name);
        t.setArtists(List.of());
        return t;
    }

    /** Builds a TrackDto with an artist and URI, for playlist-snapshot/exclusion tests. */
    private TrackDto playlistTrack(String name, String artist, String uri) {
        TrackDto.ArtistRef artistRef = new TrackDto.ArtistRef();
        artistRef.setName(artist);
        TrackDto t = new TrackDto();
        t.setName(name);
        t.setUri(uri);
        t.setArtists(List.of(artistRef));
        return t;
    }

    /** Builds a SpotifyPage<PlaylistTrackDto> with the given tracks and a real total count. */
    private SpotifyPage<PlaylistTrackDto> playlistTracksPage(int total, TrackDto... tracks) {
        SpotifyPage<PlaylistTrackDto> page = new SpotifyPage<>();
        List<PlaylistTrackDto> items = Arrays.stream(tracks)
            .map(t -> {
                PlaylistTrackDto item = new PlaylistTrackDto();
                item.setTrack(t);
                return item;
            })
            .toList();
        page.setItems(items);
        page.setTotal(total);
        return page;
    }

    /** Builds `count` distinct playlist tracks, named uniquely via the given prefix, for pagination tests. */
    private TrackDto[] pageOfTracks(int count, String prefix) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> playlistTrack(prefix + i, "Artist", prefix + "uri" + i))
            .toArray(TrackDto[]::new);
    }

    /** Stubs the Anthropic message chain to return the given raw text as the assistant reply. */
    private void stubClaudeReturns(String rawText) {
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(rawText);
        ContentBlock block = mock(ContentBlock.class);
        when(block.text()).thenReturn(Optional.of(textBlock));
        Message message = mock(Message.class);
        when(message.content()).thenReturn(List.of(block));
        when(anthropicClient.messages().create(any(MessageCreateParams.class))).thenReturn(message);
    }

    private void givenTopTracks() {
        when(spotifyApi.getTopTracks("medium_term", 10))
            .thenReturn(page(track("top1", "Top Track")));
    }

    // ── Happy path + parseSuggestions(valid array) ─────────────────────────────

    @Test
    void recommend_resolvesValidJsonArray_intoRankedTracks() {
        givenTopTracks();
        stubClaudeReturns("""
            [
              {"artist": "Artist A", "track": "Song A"},
              {"artist": "Artist B", "track": "Song B"}
            ]
            """);
        when(spotifyApi.resolveToSpotifyTrack("Artist A", "Song A"))
            .thenReturn(Optional.of(track("a", "Song A")));
        when(spotifyApi.resolveToSpotifyTrack("Artist B", "Song B"))
            .thenReturn(Optional.of(track("b", "Song B")));

        RecommendationResponse result = service.recommend(new RecommendationRequest());

        assertThat(result.getTracks()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getTracks().get(0).getTrack().getId()).isEqualTo("a");
        assertThat(result.getTracks().get(0).getScore()).isEqualTo(1.0);
    }

    // ── stripMarkdownFence(): fenced input still parses ─────────────────────────

    @Test
    void recommend_stripsMarkdownCodeFence_beforeParsing() {
        givenTopTracks();
        stubClaudeReturns("""
            ```json
            [{"artist": "Fenced Artist", "track": "Fenced Track"}]
            ```""");
        when(spotifyApi.resolveToSpotifyTrack("Fenced Artist", "Fenced Track"))
            .thenReturn(Optional.of(track("f", "Fenced Track")));

        RecommendationResponse result = service.recommend(new RecommendationRequest());

        assertThat(result.getTracks()).hasSize(1);
        assertThat(result.getTracks().get(0).getTrack().getId()).isEqualTo("f");
    }

    // ── parseSuggestions(): entries missing artist/track are skipped ────────────

    @Test
    void recommend_skipsEntriesMissingArtistOrTrack() {
        givenTopTracks();
        stubClaudeReturns("""
            [
              {"artist": "Has Both", "track": "Good"},
              {"artist": "Missing Track"},
              {"track": "Missing Artist"},
              {}
            ]
            """);
        when(spotifyApi.resolveToSpotifyTrack("Has Both", "Good"))
            .thenReturn(Optional.of(track("g", "Good")));

        RecommendationResponse result = service.recommend(new RecommendationRequest());

        assertThat(result.getTracks()).hasSize(1);
        assertThat(result.getTracks().get(0).getTrack().getId()).isEqualTo("g");
        // Incomplete entries must never reach Spotify resolution.
        verify(spotifyApi, never()).resolveToSpotifyTrack(eq("Missing Track"), anyString());
    }

    // ── resolveTracks(): honours the limit cap ─────────────────────────────────

    @Test
    void recommend_capsResultsAtRequestLimit() {
        givenTopTracks();
        stubClaudeReturns("""
            [
              {"artist": "A1", "track": "T1"},
              {"artist": "A2", "track": "T2"},
              {"artist": "A3", "track": "T3"}
            ]
            """);
        when(spotifyApi.resolveToSpotifyTrack("A1", "T1")).thenReturn(Optional.of(track("1", "T1")));
        when(spotifyApi.resolveToSpotifyTrack("A2", "T2")).thenReturn(Optional.of(track("2", "T2")));

        RecommendationRequest request = new RecommendationRequest();
        request.setLimit(2);
        RecommendationResponse result = service.recommend(request);

        assertThat(result.getTracks()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
        // Once the cap is hit, the third suggestion is never resolved.
        verify(spotifyApi, never()).resolveToSpotifyTrack("A3", "T3");
    }

    // ── resolveTracks(): unresolved suggestions are skipped ─────────────────────

    @Test
    void recommend_skipsUnresolvedSuggestions() {
        givenTopTracks();
        stubClaudeReturns("""
            [
              {"artist": "Real", "track": "Exists"},
              {"artist": "Fake", "track": "Nope"}
            ]
            """);
        when(spotifyApi.resolveToSpotifyTrack("Real", "Exists"))
            .thenReturn(Optional.of(track("r", "Exists")));
        when(spotifyApi.resolveToSpotifyTrack("Fake", "Nope"))
            .thenReturn(Optional.empty());

        RecommendationResponse result = service.recommend(new RecommendationRequest());

        assertThat(result.getTracks()).hasSize(1);
        assertThat(result.getTracks().get(0).getTrack().getId()).isEqualTo("r");
    }

    // ── Fail-safe path 1: no top tracks → empty, no Anthropic call ──────────────

    @Test
    void recommend_returnsEmpty_whenNoTopTracks() {
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(page());

        RecommendationResponse result = service.recommend(new RecommendationRequest());

        assertThat(result.getTracks()).isEmpty();
        assertThat(result.getTotal()).isZero();
        // Must not call Claude when there is nothing to base a prompt on.
        verify(anthropicClient, never()).messages();
    }

    // ── Fail-safe path 2: Anthropic throws → empty ──────────────────────────────

    @Test
    void recommend_returnsEmpty_whenAnthropicThrows() {
        givenTopTracks();
        when(anthropicClient.messages().create(any(MessageCreateParams.class)))
            .thenThrow(new RuntimeException("auth error"));

        RecommendationResponse result = service.recommend(new RecommendationRequest());

        assertThat(result.getTracks()).isEmpty();
        assertThat(result.getTotal()).isZero();
    }

    // ── Fail-safe path 3: non-JSON response → empty ─────────────────────────────

    @Test
    void recommend_returnsEmpty_whenResponseIsNotJson() {
        givenTopTracks();
        stubClaudeReturns("I'm sorry, I can't help with that request.");

        RecommendationResponse result = service.recommend(new RecommendationRequest());

        assertThat(result.getTracks()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(spotifyApi, never()).resolveToSpotifyTrack(anyString(), anyString());
    }

    // ── saveToPlaylist() ─────────────────────────────────────────────────────

    @Test
    void saveToPlaylist_addsNewTracks_whenNoDuplicates() {
        PlaylistSnapshot snapshot = new PlaylistSnapshot("p1", List.of(), 0);

        service.saveToPlaylist(List.of("uri1", "uri2"), "MyPlaylist", snapshot);

        verify(spotifyApi).addTracksToPlaylist("p1", List.of("uri1", "uri2"));
    }

    @Test
    void saveToPlaylist_skipsDuplicates_keepsOnlyNew() {
        PlaylistSnapshot snapshot = new PlaylistSnapshot(
            "p1", List.of(playlistTrack("Existing", "Artist", "uri1")), 1);

        service.saveToPlaylist(List.of("uri1", "uri2"), "MyPlaylist", snapshot);

        verify(spotifyApi).addTracksToPlaylist("p1", List.of("uri2"));
    }

    @Test
    void saveToPlaylist_nothingToAdd_afterDedup_doesNotCallAddTracks() {
        PlaylistSnapshot snapshot = new PlaylistSnapshot(
            "p1",
            List.of(playlistTrack("E1", "Artist", "uri1"), playlistTrack("E2", "Artist", "uri2")),
            2);

        service.saveToPlaylist(List.of("uri1", "uri2"), "MyPlaylist", snapshot);

        verify(spotifyApi, never()).addTracksToPlaylist(anyString(), any());
    }

    @Test
    void saveToPlaylist_skipsSave_whenPlaylistAtMaxSize() {
        PlaylistSnapshot snapshot = new PlaylistSnapshot("p1", List.of(), 200);

        service.saveToPlaylist(List.of("uri1"), "MyPlaylist", snapshot);

        verify(spotifyApi, never()).addTracksToPlaylist(anyString(), any());
    }

    // ── fetchPlaylistSnapshot() ──────────────────────────────────────────────

    @Test
    void fetchPlaylistSnapshot_returnsTracksAndRealTotal_singlePage() {
        when(spotifyApi.getOrCreatePlaylist("MyPlaylist")).thenReturn("p1");
        when(spotifyApi.getPlaylistTracks("p1", 0, 50))
            .thenReturn(playlistTracksPage(1, playlistTrack("Existing", "Artist", "uri1")));

        PlaylistSnapshot snapshot = service.fetchPlaylistSnapshot("MyPlaylist");

        assertThat(snapshot.playlistId()).isEqualTo("p1");
        assertThat(snapshot.tracks()).hasSize(1);
        assertThat(snapshot.tracks().get(0).getName()).isEqualTo("Existing");
        assertThat(snapshot.totalTrackCount()).isEqualTo(1);
        verify(spotifyApi, never()).getPlaylistTracks("p1", 50, 50);
    }

    @Test
    void fetchPlaylistSnapshot_paginatesAcrossMultiplePages() {
        when(spotifyApi.getOrCreatePlaylist("MyPlaylist")).thenReturn("p1");
        when(spotifyApi.getPlaylistTracks("p1", 0, 50))
            .thenReturn(playlistTracksPage(120, pageOfTracks(50, "p0-")));
        when(spotifyApi.getPlaylistTracks("p1", 50, 50))
            .thenReturn(playlistTracksPage(120, pageOfTracks(50, "p1-")));
        when(spotifyApi.getPlaylistTracks("p1", 100, 50))
            .thenReturn(playlistTracksPage(120, pageOfTracks(20, "p2-")));

        PlaylistSnapshot snapshot = service.fetchPlaylistSnapshot("MyPlaylist");

        assertThat(snapshot.tracks()).hasSize(120);
        assertThat(snapshot.totalTrackCount()).isEqualTo(120);
        verify(spotifyApi, never()).getPlaylistTracks("p1", 150, 50);
    }

    @Test
    void fetchPlaylistSnapshot_capsAtMaxPlaylistSize_whenTotalExceedsCap() {
        when(spotifyApi.getOrCreatePlaylist("MyPlaylist")).thenReturn("p1");
        when(spotifyApi.getPlaylistTracks(eq("p1"), anyInt(), eq(50)))
            .thenAnswer(invocation -> {
                int offset = invocation.getArgument(1);
                return playlistTracksPage(250, pageOfTracks(50, "p" + offset + "-"));
            });

        PlaylistSnapshot snapshot = service.fetchPlaylistSnapshot("MyPlaylist");

        // Capped at MAX_PLAYLIST_SIZE (200) even though the real playlist has 250 tracks.
        assertThat(snapshot.tracks()).hasSize(200);
        assertThat(snapshot.totalTrackCount()).isEqualTo(250);
        verify(spotifyApi, never()).getPlaylistTracks("p1", 200, 50);
    }

    @Test
    void fetchPlaylistSnapshot_returnsEmptySnapshot_whenGetOrCreatePlaylistThrows() {
        when(spotifyApi.getOrCreatePlaylist("MyPlaylist")).thenThrow(new RuntimeException("API error"));

        PlaylistSnapshot snapshot = service.fetchPlaylistSnapshot("MyPlaylist");

        assertThat(snapshot.playlistId()).isNull();
        assertThat(snapshot.tracks()).isEmpty();
        assertThat(snapshot.totalTrackCount()).isZero();
    }

    @Test
    void fetchPlaylistSnapshot_returnsEmptySnapshot_whenGetPlaylistTracksThrows() {
        when(spotifyApi.getOrCreatePlaylist("MyPlaylist")).thenReturn("p1");
        when(spotifyApi.getPlaylistTracks("p1", 0, 50)).thenThrow(new RuntimeException("API error"));

        PlaylistSnapshot snapshot = service.fetchPlaylistSnapshot("MyPlaylist");

        assertThat(snapshot.playlistId()).isNull();
        assertThat(snapshot.tracks()).isEmpty();
        assertThat(snapshot.totalTrackCount()).isZero();
    }

    @Test
    void fetchPlaylistSnapshot_terminates_whenPagesContainNullTrackItems() {
        when(spotifyApi.getOrCreatePlaylist("MyPlaylist")).thenReturn("p1");
        // total=3: two real tracks plus one item whose getTrack() returns null
        // (local file, unavailable track, or podcast episode)
        SpotifyPage<PlaylistTrackDto> page = playlistTracksPage(3,
            playlistTrack("Track1", "Artist1", "uri1"),
            null,
            playlistTrack("Track2", "Artist2", "uri2"));
        int[] callCount = {0};
        when(spotifyApi.getPlaylistTracks(eq("p1"), anyInt(), eq(50))).thenAnswer(invocation -> {
            if (++callCount[0] > 4) {
                throw new AssertionError("getPlaylistTracks called more than 4 times — loop did not terminate");
            }
            return page;
        });

        PlaylistSnapshot snapshot = service.fetchPlaylistSnapshot("MyPlaylist");

        assertThat(snapshot.tracks()).hasSize(2);
        assertThat(snapshot.totalTrackCount()).isEqualTo(3);
        verify(spotifyApi).getPlaylistTracks("p1", 0, 50);
        verify(spotifyApi, never()).getPlaylistTracks("p1", 50, 50);
    }

    // ── buildPrompt(): exclusion section ─────────────────────────────────────

    @Test
    void buildPrompt_includesExclusionSection_whenExistingTracksPresent() {
        String prompt = service.buildPrompt(
            List.of(track("top1", "Top Track")),
            new RecommendationRequest(),
            "Fractals-CHILL",
            List.of(playlistTrack("Already Saved", "Some Artist", "uri1")));

        assertThat(prompt).contains("Do NOT suggest any of these tracks already in the user's Fractals-CHILL playlist");
        assertThat(prompt).contains("- Already Saved by Some Artist");
    }

    @Test
    void buildPrompt_omitsExclusionSection_whenExistingTracksEmpty() {
        String prompt = service.buildPrompt(
            List.of(track("top1", "Top Track")),
            new RecommendationRequest(),
            "Fractals-CHILL",
            List.of());

        assertThat(prompt).doesNotContain("Do NOT suggest");
    }

    // ── recommend(): continues without exclusions when the playlist fetch fails ─

    @Test
    void recommend_continuesWithoutExclusions_whenPlaylistSnapshotFetchFails() {
        givenTopTracks();
        when(spotifyApi.getOrCreatePlaylist(anyString())).thenThrow(new RuntimeException("API error"));
        stubClaudeReturns("[{\"artist\": \"A\", \"track\": \"T\"}]");
        when(spotifyApi.resolveToSpotifyTrack("A", "T")).thenReturn(Optional.of(track("x", "T")));

        RecommendationResponse result = service.recommend(new RecommendationRequest());

        assertThat(result.getTracks()).hasSize(1);
        verify(spotifyApi, never()).addTracksToPlaylist(any(), any());
    }

    // ── Null request is tolerated (uses default limit) ──────────────────────────

    @Test
    void recommend_handlesNullRequest() {
        givenTopTracks();
        stubClaudeReturns("[{\"artist\": \"A\", \"track\": \"T\"}]");
        when(spotifyApi.resolveToSpotifyTrack("A", "T")).thenReturn(Optional.of(track("x", "T")));

        RecommendationResponse result = service.recommend(null);

        assertThat(result.getTracks()).hasSize(1);
    }
}
