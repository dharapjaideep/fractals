package com.spotify.recommender.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotify.recommender.model.dto.chat.ChatRequest;
import com.spotify.recommender.model.dto.chat.ChatResponse;
import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.PlaylistTrackDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.model.entity.ChatLog;
import com.spotify.recommender.repository.AppUserRepository;
import com.spotify.recommender.repository.ChatLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private SpotifyApiService spotifyApi;

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private ChatLogRepository chatLogRepository;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private AnthropicClient anthropicClient;

    @Mock
    private EmbeddingService embeddingService;

    private ChatService service;

    @BeforeEach
    void setUp() {
        // embeddingService.findRelevantTracks() returns an empty List by default
        // (Mockito default for List-returning methods), so all existing tests
        // fall through to the top-tracks context path unchanged.
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

    private TrackDto track(String id, String name, String artist, String uri) {
        TrackDto.ArtistRef ref = new TrackDto.ArtistRef();
        ref.setName(artist);
        TrackDto t = new TrackDto();
        t.setId(id);
        t.setName(name);
        t.setUri(uri);
        t.setArtists(List.of(ref));
        return t;
    }

    private SpotifyPage<TrackDto> topTracksPage() {
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(List.of(track("top1", "Top Track", "Top Artist", "spotify:track:top1")));
        return page;
    }

    private ChatLog chatLogEntry(String userMessage, String response) {
        ChatLog entry = new ChatLog();
        entry.setUserId(1L);
        entry.setUserMessage(userMessage);
        entry.setResponse(response);
        return entry;
    }

    private SpotifyPage<PlaylistTrackDto> emptyPlaylistPage() {
        SpotifyPage<PlaylistTrackDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        page.setTotal(0);
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

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    void chat_returnsChatResponseWithTracks_happyPath() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        stubClaudeReturns("""
            {"message": "Here are some chill tracks!", "mood": "CHILL", "playlistName": "Chill Vibes", "recommendations": [
              {"artist": "Khruangbin", "track": "Texas Sun"}
            ]}
            """);
        TrackDto resolved = track("t1", "Texas Sun", "Khruangbin", "spotify:track:t1");
        when(spotifyApi.resolveToSpotifyTrack("Khruangbin", "Texas Sun")).thenReturn(Optional.of(resolved));
        when(spotifyApi.getOrCreatePlaylist("Fractals-Chill Vibes")).thenReturn("p1");
        when(spotifyApi.getPlaylistTracks("p1", 0, 50)).thenReturn(emptyPlaylistPage());

        ChatResponse response = service.chat(new ChatRequest("give me chill songs"), "test-user");

        assertThat(response.getMessage()).isEqualTo("Here are some chill tracks!");
        assertThat(response.getMood()).isEqualTo("CHILL");
        assertThat(response.getTracks()).hasSize(1);
        assertThat(response.getTracks().get(0).getTrack().getId()).isEqualTo("t1");
        assertThat(response.getPlaylistName()).isEqualTo("Fractals-Chill Vibes");
        assertThat(response.getPlaylistId()).isEqualTo("p1");
        verify(spotifyApi).addTracksToPlaylist("p1", List.of("spotify:track:t1"));
        verify(chatLogRepository).save(any(ChatLog.class));
    }

    @Test
    void chat_usesDefaultPlaylistName_whenClaudeOmitsPlaylistName() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        stubClaudeReturns("""
            {"message": "Here you go.", "recommendations": [
              {"artist": "Khruangbin", "track": "Texas Sun"}
            ]}
            """);
        when(spotifyApi.resolveToSpotifyTrack("Khruangbin", "Texas Sun"))
            .thenReturn(Optional.of(track("t1", "Texas Sun", "Khruangbin", "spotify:track:t1")));
        when(spotifyApi.getOrCreatePlaylist("Fractals-Default")).thenReturn("p1");
        when(spotifyApi.getPlaylistTracks("p1", 0, 50)).thenReturn(emptyPlaylistPage());

        ChatResponse response = service.chat(new ChatRequest("play me something"), "test-user");

        assertThat(response.getMood()).isNull();
        assertThat(response.getPlaylistName()).isEqualTo("Fractals-Default");
    }

    @Test
    void chat_usesDefaultPlaylist_whenMoodUnrecognized() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        stubClaudeReturns("""
            {"message": "Here you go.", "mood": "SUPER_VIBES", "recommendations": [
              {"artist": "Khruangbin", "track": "Texas Sun"}
            ]}
            """);
        when(spotifyApi.resolveToSpotifyTrack("Khruangbin", "Texas Sun"))
            .thenReturn(Optional.of(track("t1", "Texas Sun", "Khruangbin", "spotify:track:t1")));
        when(spotifyApi.getOrCreatePlaylist("Fractals-Default")).thenReturn("p1");
        when(spotifyApi.getPlaylistTracks("p1", 0, 50)).thenReturn(emptyPlaylistPage());

        ChatResponse response = service.chat(new ChatRequest("play me something"), "test-user");

        assertThat(response.getMood()).isEqualTo("SUPER_VIBES");   // raw mood string preserved
        assertThat(response.getPlaylistName()).isEqualTo("Fractals-Default"); // no playlistName from Claude → default
    }

    // ── Fail-safe: Anthropic throws → error response, log still written ────────

    @Test
    void chat_returnsErrorResponse_whenAnthropicThrows() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(anthropicClient.messages().create(any(MessageCreateParams.class)))
            .thenThrow(new RuntimeException("Anthropic error"));

        ChatResponse response = service.chat(new ChatRequest("give me music"), "test-user");

        assertThat(response.getMessage()).contains("trouble");
        assertThat(response.getTracks()).isEmpty();
        assertThat(response.getPlaylistName()).isNull();
        // Log written with null response field (user message still captured)
        verify(chatLogRepository).save(any(ChatLog.class));
    }

    // ── Fail-safe: non-JSON response → raw text returned as message ────────────

    @Test
    void chat_returnsRawText_whenResponseNotJson() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        stubClaudeReturns("Not JSON at all");

        ChatResponse response = service.chat(new ChatRequest("what is music"), "test-user");

        assertThat(response.getMessage()).isEqualTo("Not JSON at all");
        assertThat(response.getTracks()).isEmpty();
        assertThat(response.getPlaylistName()).isNull();
        verify(spotifyApi, never()).getOrCreatePlaylist(anyString());
    }

    // ── Non-music redirect → empty recommendations array ─────────────────────

    @Test
    void chat_returnsEmptyTracks_whenRecommendationsArrayIsEmpty() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        stubClaudeReturns("""
            {"message": "I'm here to help you discover music. What are you in the mood for?",
             "recommendations": []}
            """);

        ChatResponse response = service.chat(new ChatRequest("who are you"), "test-user");

        assertThat(response.getMessage()).contains("help you discover music");
        assertThat(response.getTracks()).isEmpty();
        assertThat(response.getPlaylistName()).isNull();
        verify(spotifyApi, never()).getOrCreatePlaylist(anyString());
    }

    // ── Fail-safe: top tracks fetch fails → still calls Claude ───────────────

    @Test
    void chat_continuesWithoutContext_whenTopTracksFetchFails() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenThrow(new RuntimeException("Spotify error"));
        stubClaudeReturns("""
            {"message": "Sure, here you go.", "recommendations": []}
            """);

        ChatResponse response = service.chat(new ChatRequest("give me music"), "test-user");

        assertThat(response.getMessage()).isEqualTo("Sure, here you go.");
        // Claude was still called despite top-tracks failure
        verify(anthropicClient.messages()).create(any(MessageCreateParams.class));
    }

    // ── Conversational memory ─────────────────────────────────────────────────

    @Test
    void chat_prependsTenHistoricalExchangesWhenAvailable() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());

        ChatLog h1 = chatLogEntry("first request", "{\"message\":\"first response\"}");
        ChatLog h2 = chatLogEntry("second request", "{\"message\":\"second response\"}");
        ChatLog h3 = chatLogEntry("third request", "{\"message\":\"third response\"}");
        ChatLog h4 = chatLogEntry("fourth request", "{\"message\":\"fourth response\"}");
        ChatLog h5 = chatLogEntry("fifth request", "{\"message\":\"fifth response\"}");
        ChatLog h6 = chatLogEntry("sixth request", "{\"message\":\"sixth response\"}");
        ChatLog h7 = chatLogEntry("seventh request", "{\"message\":\"seventh response\"}");
        ChatLog h8 = chatLogEntry("eighth request", "{\"message\":\"eighth response\"}");
        ChatLog h9 = chatLogEntry("ninth request", "{\"message\":\"ninth response\"}");
        ChatLog h10 = chatLogEntry("tenth request", "{\"message\":\"tenth response\"}");
        // Repository returns newest-first, exactly as the real derived query would
        when(chatLogRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L))
            .thenReturn(List.of(h10, h9, h8, h7, h6, h5, h4, h3, h2, h1));

        stubClaudeReturns("""
            {"message": "Here you go.", "recommendations": []}
            """);

        service.chat(new ChatRequest("eleventh request"), "test-user");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        List<MessageParam> messages = captor.getValue().messages();

        assertThat(messages).hasSize(21); // 10 historical pairs + current turn
        assertThat(messages.get(0).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(0).content().asString()).isEqualTo("first request");
        assertThat(messages.get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(1).content().asString()).isEqualTo("first response");
        assertThat(messages.get(2).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(2).content().asString()).isEqualTo("second request");
        assertThat(messages.get(3).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(3).content().asString()).isEqualTo("second response");
        assertThat(messages.get(4).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(4).content().asString()).isEqualTo("third request");
        assertThat(messages.get(5).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(5).content().asString()).isEqualTo("third response");
        assertThat(messages.get(6).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(6).content().asString()).isEqualTo("fourth request");
        assertThat(messages.get(7).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(7).content().asString()).isEqualTo("fourth response");
        assertThat(messages.get(8).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(8).content().asString()).isEqualTo("fifth request");
        assertThat(messages.get(9).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(9).content().asString()).isEqualTo("fifth response");
        assertThat(messages.get(10).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(10).content().asString()).isEqualTo("sixth request");
        assertThat(messages.get(11).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(11).content().asString()).isEqualTo("sixth response");
        assertThat(messages.get(12).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(12).content().asString()).isEqualTo("seventh request");
        assertThat(messages.get(13).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(13).content().asString()).isEqualTo("seventh response");
        assertThat(messages.get(14).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(14).content().asString()).isEqualTo("eighth request");
        assertThat(messages.get(15).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(15).content().asString()).isEqualTo("eighth response");
        assertThat(messages.get(16).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(16).content().asString()).isEqualTo("ninth request");
        assertThat(messages.get(17).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(17).content().asString()).isEqualTo("ninth response");
        assertThat(messages.get(18).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(18).content().asString()).isEqualTo("tenth request");
        assertThat(messages.get(19).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(19).content().asString()).isEqualTo("tenth response");
        assertThat(messages.get(20).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(20).content().asString()).contains("User: eleventh request");
    }

    @Test
    void chat_prependsOneHistoricalExchangeWhenOnlyOneExists() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());

        ChatLog h1 = chatLogEntry("only prior request", "{\"message\":\"only prior response\"}");
        when(chatLogRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(h1));

        stubClaudeReturns("""
            {"message": "Here you go.", "recommendations": []}
            """);

        service.chat(new ChatRequest("new request"), "test-user");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        List<MessageParam> messages = captor.getValue().messages();

        assertThat(messages).hasSize(3); // 1 historical pair + current turn — not padded to 10
        assertThat(messages.get(0).content().asString()).isEqualTo("only prior request");
        assertThat(messages.get(1).content().asString()).isEqualTo("only prior response");
        assertThat(messages.get(2).content().asString()).contains("User: new request");
    }

    @Test
    void chat_sendsSingleMessageWhenNoHistoryExists() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(chatLogRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        stubClaudeReturns("""
            {"message": "Here you go.", "recommendations": []}
            """);

        service.chat(new ChatRequest("first ever request"), "test-user");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(1); // current turn only — unchanged existing behaviour
    }

    @Test
    void chat_fallsBackToSingleMessageWhenHistoryFetchFails() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(chatLogRepository.findTop10ByUserIdOrderByCreatedAtDesc(1L))
            .thenThrow(new RuntimeException("DB error"));

        stubClaudeReturns("""
            {"message": "Here you go.", "recommendations": []}
            """);

        ChatResponse response = service.chat(new ChatRequest("give me music"), "test-user");

        assertThat(response.getMessage()).isEqualTo("Here you go.");
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(1); // fail-safe: proceeds with current turn only
    }

    @Test
    void chat_historyFetchUsesUserIdNotSpotifyId() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(42L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        stubClaudeReturns("""
            {"message": "Here you go.", "recommendations": []}
            """);

        service.chat(new ChatRequest("give me music"), "test-user");

        verify(chatLogRepository).findTop10ByUserIdOrderByCreatedAtDesc(42L);
    }

    // ── Log not written when user lookup fails (no userId to FK against) ──────

    @Test
    void chat_skipsLogPersistence_whenUserNotFound() {
        when(userRepository.findBySpotifyId("unknown")).thenReturn(Optional.empty());
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        stubClaudeReturns("""
            {"message": "Hello!", "recommendations": []}
            """);

        ChatResponse response = service.chat(new ChatRequest("hi"), "unknown");

        assertThat(response.getMessage()).isEqualTo("Hello!");
        verify(chatLogRepository, never()).save(any());
    }

    // ── Playlist context detection ────────────────────────────────────────────

    private SpotifyPage<PlaylistDto> fullNonMatchingPage(int count) {
        List<PlaylistDto> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PlaylistDto p = new PlaylistDto();
            p.setId("nonmatch" + i);
            p.setName("Playlist " + String.format("%02d", i));
            items.add(p);
        }
        SpotifyPage<PlaylistDto> page = new SpotifyPage<>();
        page.setItems(items);
        if (count == 50) {
            // Full page — mirrors what Spotify sets when more pages follow. Pagination in
            // findMentionedPlaylist() now uses page.getNext() == null as the stop signal
            // (instead of items.size() < PAGE) so this must be non-null to allow page 2.
            page.setNext("https://api.spotify.com/v1/me/playlists?offset=50&limit=50");
        }
        return page;
    }

    private SpotifyPage<PlaylistDto> playlistPage(String id, String name) {
        PlaylistDto playlist = new PlaylistDto();
        playlist.setId(id);
        playlist.setName(name);
        SpotifyPage<PlaylistDto> page = new SpotifyPage<>();
        page.setItems(List.of(playlist));
        return page;
    }

    private SpotifyPage<PlaylistTrackDto> playlistTrackPage(TrackDto... tracks) {
        List<PlaylistTrackDto> items = java.util.Arrays.stream(tracks).map(t -> {
            PlaylistTrackDto ptd = new PlaylistTrackDto();
            ptd.setTrack(t);
            return ptd;
        }).toList();
        SpotifyPage<PlaylistTrackDto> page = new SpotifyPage<>();
        page.setItems(items);
        return page;
    }

    @Test
    void chat_includesPlaylistContext_whenUserMentionsPlaylist() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(playlistPage("pl1", "Guilty Pleasure"));
        TrackDto plTrack = track("pltrack1", "Shape of You", "Ed Sheeran", "spotify:track:pltrack1");
        when(spotifyApi.getPlaylistTracks("pl1", 0, 100)).thenReturn(playlistTrackPage(plTrack));
        // Pre-fetch stubs: target playlist "Fractals-Guilty Pleasure" for exclusion lookup
        when(spotifyApi.getOrCreatePlaylist("Fractals-Guilty Pleasure")).thenReturn("fractals-pl1");
        when(spotifyApi.getPlaylistTracks("fractals-pl1", 0, 100)).thenReturn(emptyPlaylistPage());
        stubClaudeReturns("""
            {"message": "Based on your Guilty Pleasure playlist!", "mood": "HAPPY", "recommendations": []}
            """);

        ChatResponse response = service.chat(
            new ChatRequest("something like my Guilty Pleasure playlist"), "test-user");

        assertThat(response.getMessage()).isEqualTo("Based on your Guilty Pleasure playlist!");
        verify(spotifyApi).getPlaylistTracks("pl1", 0, 100);
    }

    @Test
    void chat_usesTopTracksOnly_whenNoPlaylistMatch() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(playlistPage("pl1", "Workout Mix"));
        stubClaudeReturns("""
            {"message": "Here are chill tracks.", "recommendations": []}
            """);

        service.chat(new ChatRequest("chill songs please"), "test-user");

        verify(spotifyApi, never()).getPlaylistTracks(anyString(), anyInt(), anyInt());
    }

    @Test
    void chat_findsPlaylistMatchOnSecondPage() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        // First page is full (50 items, none matching) — triggers pagination
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(fullNonMatchingPage(50));
        // Second page contains the match
        when(spotifyApi.getPlaylists(50, 50)).thenReturn(playlistPage("pl2", "Workout Mix"));
        TrackDto plTrack = track("wt1", "Eye of the Tiger", "Survivor", "spotify:track:wt1");
        when(spotifyApi.getPlaylistTracks("pl2", 0, 100)).thenReturn(playlistTrackPage(plTrack));
        // Pre-fetch stubs: target playlist "Fractals-Workout Mix" for exclusion lookup
        when(spotifyApi.getOrCreatePlaylist("Fractals-Workout Mix")).thenReturn("fractals-pl2");
        when(spotifyApi.getPlaylistTracks("fractals-pl2", 0, 100)).thenReturn(emptyPlaylistPage());
        stubClaudeReturns("""
            {"message": "Pumping tunes!", "mood": "WORKOUT", "recommendations": []}
            """);

        service.chat(new ChatRequest("something for my Workout Mix playlist"), "test-user");

        verify(spotifyApi).getPlaylistTracks("pl2", 0, 100);
    }

    @Test
    void chat_savesToPlaylistPrefixedName_whenPlaylistMentioned() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(playlistPage("pl1", "Guilty Pleasure"));
        TrackDto plTrack = track("pltrack1", "Shape of You", "Ed Sheeran", "spotify:track:pltrack1");
        when(spotifyApi.getPlaylistTracks("pl1", 0, 100)).thenReturn(playlistTrackPage(plTrack));
        stubClaudeReturns("""
            {"message": "Based on your Guilty Pleasure playlist!", "mood": "HAPPY", "recommendations": [
              {"artist": "Katy Perry", "track": "Roar"}
            ]}
            """);
        TrackDto resolved = track("t1", "Roar", "Katy Perry", "spotify:track:t1");
        when(spotifyApi.resolveToSpotifyTrack("Katy Perry", "Roar")).thenReturn(Optional.of(resolved));
        // getOrCreatePlaylist is called twice: once for the pre-fetch exclusion check (before Claude),
        // and once inside saveToPlaylist (after Claude). Both calls return "p2".
        when(spotifyApi.getOrCreatePlaylist("Fractals-Guilty Pleasure")).thenReturn("p2");
        when(spotifyApi.getPlaylistTracks("p2", 0, 100)).thenReturn(emptyPlaylistPage()); // pre-fetch
        when(spotifyApi.getPlaylistTracks("p2", 0, 50)).thenReturn(emptyPlaylistPage());  // saveToPlaylist dedup

        ChatResponse response = service.chat(
            new ChatRequest("something like my Guilty Pleasure playlist"), "test-user");

        assertThat(response.getPlaylistName()).isEqualTo("Fractals-Guilty Pleasure");
        verify(spotifyApi, times(2)).getOrCreatePlaylist("Fractals-Guilty Pleasure");
        // Must NOT save to mood-derived name
        verify(spotifyApi, never()).getOrCreatePlaylist("Fractals-HAPPY");
    }

    @Test
    void chat_doesNotDoublePrefixFractalsPlaylistName() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        // Fractals-* playlist is present in the library but filtered out by findMentionedPlaylist;
        // tracks are never fetched for it, so no getPlaylistTracks stub is needed here.
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(playlistPage("pl3", "Fractals-Workout Mix"));
        stubClaudeReturns("""
            {"message": "More from Fractals-Workout Mix!", "mood": "WORKOUT", "recommendations": [
              {"artist": "Survivor", "track": "Eye of the Tiger"}
            ]}
            """);
        TrackDto resolved = track("t3", "Eye of the Tiger", "Survivor", "spotify:track:t3");
        when(spotifyApi.resolveToSpotifyTrack("Survivor", "Eye of the Tiger")).thenReturn(Optional.of(resolved));
        when(spotifyApi.getOrCreatePlaylist("Fractals-Default")).thenReturn("p3");
        when(spotifyApi.getPlaylistTracks("p3", 0, 50)).thenReturn(emptyPlaylistPage());

        ChatResponse response = service.chat(
            new ChatRequest("something from my Fractals-Workout Mix playlist"), "test-user");

        // Fractals-* playlists are excluded from matching; no playlistName from Claude → default
        assertThat(response.getPlaylistName()).isEqualTo("Fractals-Default");
        verify(spotifyApi).getOrCreatePlaylist("Fractals-Default");
        verify(spotifyApi, never()).getOrCreatePlaylist("Fractals-Fractals-Workout Mix");
    }

    @Test
    void chat_continuesWithTopTracksOnly_whenPlaylistFetchFails() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getPlaylists(0, 50)).thenThrow(new RuntimeException("Spotify error"));
        stubClaudeReturns("""
            {"message": "Sure!", "recommendations": []}
            """);

        ChatResponse response = service.chat(new ChatRequest("give me music"), "test-user");

        assertThat(response.getMessage()).isEqualTo("Sure!");
        verify(anthropicClient.messages()).create(any(MessageCreateParams.class));
    }

    // ── Claude-generated playlist name ───────────────────────────────────────

    @Test
    void chat_usesClaudePlaylistName_whenProvided() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        stubClaudeReturns("""
            {"message": "Late night city vibes!", "mood": "CHILL",
             "playlistName": "Late City Nights",
             "recommendations": [{"artist": "Khruangbin", "track": "Texas Sun"}]}
            """);
        when(spotifyApi.resolveToSpotifyTrack("Khruangbin", "Texas Sun"))
            .thenReturn(Optional.of(track("t1", "Texas Sun", "Khruangbin", "spotify:track:t1")));
        when(spotifyApi.getOrCreatePlaylist("Fractals-Late City Nights")).thenReturn("p1");
        when(spotifyApi.getPlaylistTracks("p1", 0, 50)).thenReturn(emptyPlaylistPage());

        ChatResponse response = service.chat(new ChatRequest("late night city music"), "test-user");

        assertThat(response.getPlaylistName()).isEqualTo("Fractals-Late City Nights");
        verify(spotifyApi).getOrCreatePlaylist("Fractals-Late City Nights");
        verify(spotifyApi, never()).getOrCreatePlaylist("Fractals-Default");
    }

    // ── Exclusion of existing target playlist tracks ──────────────────────────

    @Test
    void chat_excludesExistingTargetPlaylistTracks_fromClaudePrompt() {
        when(userRepository.findBySpotifyId("test-user")).thenReturn(Optional.of(user(1L)));
        when(spotifyApi.getTopTracks("medium_term", 10)).thenReturn(topTracksPage());
        when(spotifyApi.getPlaylists(0, 50)).thenReturn(playlistPage("pl1", "Guilty Pleasure"));
        TrackDto plTrack = track("pltrack1", "Shape of You", "Ed Sheeran", "spotify:track:pltrack1");
        when(spotifyApi.getPlaylistTracks("pl1", 0, 100)).thenReturn(playlistTrackPage(plTrack));
        // Target playlist already contains "Roar" — Claude must be told to skip it
        when(spotifyApi.getOrCreatePlaylist("Fractals-Guilty Pleasure")).thenReturn("p2");
        TrackDto existingTrack = track("existing1", "Roar", "Katy Perry", "spotify:track:existing1");
        when(spotifyApi.getPlaylistTracks("p2", 0, 100)).thenReturn(playlistTrackPage(existingTrack));
        stubClaudeReturns("""
            {"message": "Here are fresh picks!", "mood": "HAPPY", "recommendations": []}
            """);

        service.chat(new ChatRequest("something like my Guilty Pleasure playlist"), "test-user");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicClient.messages()).create(captor.capture());
        // The MessageCreateParams toString includes all message content; assert the exclusion block
        // is present so Claude sees the track it must not re-suggest.
        String paramsStr = captor.getValue().toString();
        assertThat(paramsStr).contains("do NOT suggest these");
        assertThat(paramsStr).contains("Fractals-Guilty Pleasure");
        assertThat(paramsStr).contains("Roar by Katy Perry");
    }
}