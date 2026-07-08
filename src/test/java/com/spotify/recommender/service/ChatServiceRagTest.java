package com.spotify.recommender.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotify.recommender.model.dto.chat.ChatRequest;
import com.spotify.recommender.model.dto.chat.ChatResponse;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import com.spotify.recommender.repository.ChatLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ChatService}'s RAG integration — verifying that the user-turn message
 * sent to Claude uses semantic retrieval context when available, and falls back to top-tracks
 * when RAG returns too few results or fails.
 *
 * <p>RAG injection is currently disabled in ChatService (see the RAG DISABLED comment there).
 * Tests asserting that RAG IS injected are {@link Disabled} until re-enabled; tests asserting
 * the top-tracks fallback remain active and pass trivially. LENIENT strictness prevents
 * UnnecessaryStubbingException on the {@code findRelevantTracks} stubs that go uncalled.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceRagTest {

    @Mock private SpotifyApiService spotifyApi;
    @Mock private AppUserRepository userRepository;
    @Mock private ChatLogRepository chatLogRepository;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private AnthropicClient anthropicClient;
    @Mock private EmbeddingService embeddingService;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(
            spotifyApi, userRepository, chatLogRepository,
            anthropicClient, new ObjectMapper(), "claude-sonnet-4-6", 1000L, "",
            embeddingService);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AppUser user(long id) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setSpotifyId("test-user");
        return u;
    }

    // Three tracks — enough to be classified as a NORMAL profile (mediumTermTracks.size() >= 3),
    // so these tests exercise RAG fallback behavior rather than the THIN/NONE profile-strength labels.
    private SpotifyPage<TrackDto> topTracksPage() {
        TrackDto.ArtistRef ref = new TrackDto.ArtistRef();
        ref.setName("Top Artist");
        List<TrackDto> tracks = List.of("top1", "top2", "top3").stream()
            .map(id -> {
                TrackDto t = new TrackDto();
                t.setId(id);
                t.setName("Top Track " + id);
                t.setArtists(List.of(ref));
                return t;
            })
            .toList();
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(tracks);
        return page;
    }

    private void stubClaudeReturns(String text) {
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(text);
        ContentBlock block = mock(ContentBlock.class);
        when(block.text()).thenReturn(Optional.of(textBlock));
        Message message = mock(Message.class);
        when(message.content()).thenReturn(List.of(block));
        when(anthropicClient.messages().create(any(MessageCreateParams.class))).thenReturn(message);
    }

    private List<String> fiveRagTracks() {
        return List.of(
            "Texas Sun by Khruangbin, from Texas Sun",
            "Be My Mistake by The 1975",
            "Ribs by Lorde, from Pure Heroine",
            "Motion Picture Soundtrack by Radiohead",
            "I'll Be Your Mirror by The Velvet Underground"
        );
    }

    // ── RAG context injected when 5+ tracks returned ──────────────────────────

    @Test
    @Disabled("RAG injection disabled in ChatService — re-enable this test when ChatService RAG injection is re-enabled")
    void chat_injectsRagContextWhenFiveOrMoreTracksReturned() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getTopTracks("short_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(new SpotifyPage<>());
        when(embeddingService.findRelevantTracks(1L, "something melancholy", 35))
            .thenReturn(fiveRagTracks());
        stubClaudeReturns("{\"message\": \"Here you go.\", \"recommendations\": []}");

        service.chat(new ChatRequest("something melancholy"), "test-user");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        String params = captor.getValue().toString();
        assertThat(params).contains("Tracks from your library most relevant to this request");
        assertThat(params).contains("Texas Sun by Khruangbin");
        // The blunt top-tracks header must not appear alongside the RAG header
        assertThat(params).doesNotContain("Context — user's recent top tracks");
    }

    // ── Falls back to top-tracks when RAG returns fewer than 5 ───────────────

    @Test
    void chat_fallsBackToTopTracksWhenRagReturnsFourTracks() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getTopTracks("short_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(new SpotifyPage<>());
        when(embeddingService.findRelevantTracks(1L, "rainy afternoon vibes", 35))
            .thenReturn(List.of("Track A", "Track B", "Track C", "Track D")); // only 4
        stubClaudeReturns("{\"message\": \"Here you go.\", \"recommendations\": []}");

        service.chat(new ChatRequest("rainy afternoon vibes"), "test-user");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        String params = captor.getValue().toString();
        assertThat(params).contains("Context — user's recent top tracks");
        assertThat(params).doesNotContain("Tracks from your library most relevant");
    }

    // ── Falls back to top-tracks when RAG returns empty ───────────────────────

    @Test
    void chat_fallsBackToTopTracksWhenRagReturnsEmpty() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getTopTracks("short_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(new SpotifyPage<>());
        when(embeddingService.findRelevantTracks(1L, "upbeat workout songs", 35))
            .thenReturn(List.of());
        stubClaudeReturns("{\"message\": \"Here you go.\", \"recommendations\": []}");

        service.chat(new ChatRequest("upbeat workout songs"), "test-user");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        String params = captor.getValue().toString();
        assertThat(params).contains("Context — user's recent top tracks");
        assertThat(params).doesNotContain("Tracks from your library most relevant");
    }

    // ── Playlist context still injected alongside RAG context ─────────────────

    @Test
    @Disabled("RAG injection disabled in ChatService — re-enable this test when ChatService RAG injection is re-enabled")
    void chat_includesPlaylistContextAlongsideRagContext() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getTopTracks("short_term", 10)).thenReturn(topTracksPage());

        // Set up playlist "Late Night" to be matched
        com.spotify.recommender.model.dto.spotify.PlaylistDto playlist =
            new com.spotify.recommender.model.dto.spotify.PlaylistDto();
        playlist.setId("pl1");
        playlist.setName("Late Night");
        SpotifyPage<com.spotify.recommender.model.dto.spotify.PlaylistDto> plPage = new SpotifyPage<>();
        plPage.setItems(List.of(playlist));
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(plPage);

        com.spotify.recommender.model.dto.spotify.PlaylistTrackDto pt =
            new com.spotify.recommender.model.dto.spotify.PlaylistTrackDto();
        TrackDto.ArtistRef ref = new TrackDto.ArtistRef();
        ref.setName("Khruangbin");
        TrackDto plTrack = new TrackDto();
        plTrack.setId("t99");
        plTrack.setName("Texas Sun");
        plTrack.setArtists(List.of(ref));
        pt.setTrack(plTrack);
        SpotifyPage<com.spotify.recommender.model.dto.spotify.PlaylistTrackDto> trackPage =
            new SpotifyPage<>();
        trackPage.setItems(List.of(pt));
        when(spotifyApi.getPlaylistTracks("pl1", 0, 100)).thenReturn(trackPage);

        // Target playlist for exclusion fetch
        when(spotifyApi.getOrCreatePlaylist("Fractals-Late Night")).thenReturn("fractals-pl1");
        SpotifyPage<com.spotify.recommender.model.dto.spotify.PlaylistTrackDto> emptyPage =
            new SpotifyPage<>();
        emptyPage.setItems(List.of());
        emptyPage.setTotal(0);
        when(spotifyApi.getPlaylistTracks("fractals-pl1", 0, 100)).thenReturn(emptyPage);

        when(embeddingService.findRelevantTracks(1L, "songs like my Late Night playlist", 35))
            .thenReturn(fiveRagTracks());
        stubClaudeReturns("{\"message\": \"Late night vibes!\", \"recommendations\": []}");

        service.chat(new ChatRequest("songs like my Late Night playlist"), "test-user");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        String params = captor.getValue().toString();
        // Both RAG context and playlist context should be present
        assertThat(params).contains("Tracks from your library most relevant to this request");
        assertThat(params).contains("User mentioned their 'Late Night' playlist");
    }
}
