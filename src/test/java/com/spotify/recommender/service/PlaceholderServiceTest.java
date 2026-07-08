package com.spotify.recommender.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotify.recommender.model.dto.placeholder.PlaceholderResponse;
import com.spotify.recommender.model.dto.spotify.PlaylistDto;
import com.spotify.recommender.model.dto.spotify.SpotifyPage;
import com.spotify.recommender.model.dto.spotify.TrackDto;
import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlaceholderService}.
 */
@ExtendWith(MockitoExtension.class)
class PlaceholderServiceTest {

    @Mock
    private SpotifyApiService spotifyApi;

    @Mock
    private OAuth2AuthorizedClientService authorizedClientService;

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private AnthropicClient anthropicClient;

    private PlaceholderService service;

    private AppUser user;

    @BeforeEach
    void setUp() {
        service = new PlaceholderService(
            spotifyApi, authorizedClientService, userRepository, jdbcTemplate,
            anthropicClient, new ObjectMapper(), "claude-sonnet-4-6", 1000L);
        user = new AppUser();
        user.setId(1L);
        user.setSpotifyId("spotify-abc");
    }

    private OAuth2AuthorizedClient mockAuthorizedClient(String tokenValue) {
        OAuth2AccessToken token = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, tokenValue,
            Instant.now(), Instant.now().plusSeconds(3600));
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getAccessToken()).thenReturn(token);
        return client;
    }

    private void stubAnthropicResponse(String text) {
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(text);
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.text()).thenReturn(java.util.Optional.of(textBlock));
        Message message = mock(Message.class);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(anthropicClient.messages().create(any(MessageCreateParams.class))).thenReturn(message);
    }

    private static String validJson() {
        return "{\"mood\": \"something to listen to at 2am\", "
             + "\"playlist\": \"something like my Jazz playlist\", "
             + "\"feeling\": \"rain on train windows and something left unsaid\"}";
    }

    private PlaylistDto playlist(String name, int trackCount) {
        PlaylistDto p = new PlaylistDto();
        p.setName(name);
        PlaylistDto.TracksRef tracks = new PlaylistDto.TracksRef();
        tracks.setTotal(trackCount);
        p.setTracks(tracks);
        return p;
    }

    // ── regenerate() ──────────────────────────────────────────────────────────

    @Test
    void regenerate_upsertsPlaceholderFields_onSuccess() {
        OAuth2AuthorizedClient client = mockAuthorizedClient("tok-123");
        when(authorizedClientService.loadAuthorizedClient("spotify", "spotify-abc"))
            .thenReturn(client);
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        when(spotifyApi.getTopTracksWithToken(eq("tok-123"), anyString(), anyInt()))
            .thenReturn(page);
        stubAnthropicResponse(validJson());

        service.regenerate(user);

        verify(jdbcTemplate).update(anyString(), eq(1L),
            eq("something to listen to at 2am"),
            eq("something like my Jazz playlist"),
            eq("rain on train windows and something left unsaid"));
    }

    @Test
    void regenerate_doesNotUpsert_whenNoAuthorizedClient() {
        when(authorizedClientService.loadAuthorizedClient("spotify", "spotify-abc"))
            .thenReturn(null);

        service.regenerate(user);

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void regenerate_doesNotUpsert_whenAnthropicFails() {
        OAuth2AuthorizedClient client = mockAuthorizedClient("tok-123");
        when(authorizedClientService.loadAuthorizedClient("spotify", "spotify-abc"))
            .thenReturn(client);
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        when(spotifyApi.getTopTracksWithToken(anyString(), anyString(), anyInt()))
            .thenReturn(page);
        when(anthropicClient.messages().create(any(MessageCreateParams.class)))
            .thenThrow(new RuntimeException("Anthropic unavailable"));

        service.regenerate(user);

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void regenerate_doesNotUpsert_whenAnthropicReturnsUnparseableJson() {
        OAuth2AuthorizedClient client = mockAuthorizedClient("tok-123");
        when(authorizedClientService.loadAuthorizedClient("spotify", "spotify-abc"))
            .thenReturn(client);
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        when(spotifyApi.getTopTracksWithToken(anyString(), anyString(), anyInt()))
            .thenReturn(page);
        stubAnthropicResponse("   ");  // blank — not valid JSON

        service.regenerate(user);

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void regenerate_doesNotUpsert_whenJsonMissingAField() {
        OAuth2AuthorizedClient client = mockAuthorizedClient("tok-123");
        when(authorizedClientService.loadAuthorizedClient("spotify", "spotify-abc"))
            .thenReturn(client);
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        when(spotifyApi.getTopTracksWithToken(anyString(), anyString(), anyInt()))
            .thenReturn(page);
        stubAnthropicResponse("{\"mood\": \"something chill\", \"playlist\": \"something like my Jazz playlist\"}");

        service.regenerate(user);

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void regenerate_continuesWithEmptyTracks_whenSpotifyTopTracksFail() {
        OAuth2AuthorizedClient client = mockAuthorizedClient("tok-123");
        when(authorizedClientService.loadAuthorizedClient("spotify", "spotify-abc"))
            .thenReturn(client);
        when(spotifyApi.getTopTracksWithToken(anyString(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("Spotify down"));
        stubAnthropicResponse(validJson());

        // Should not throw — Spotify failure is caught internally and Anthropic is still called
        service.regenerate(user);

        verify(jdbcTemplate).update(anyString(), eq(1L), anyString(), anyString(), anyString());
    }

    @Test
    void regenerate_selectsLargestOwnedPlaylistByTrackCount() {
        OAuth2AuthorizedClient client = mockAuthorizedClient("tok-123");
        when(authorizedClientService.loadAuthorizedClient("spotify", "spotify-abc"))
            .thenReturn(client);
        SpotifyPage<TrackDto> tracksPage = new SpotifyPage<>();
        tracksPage.setItems(List.of());
        when(spotifyApi.getTopTracksWithToken(anyString(), anyString(), anyInt()))
            .thenReturn(tracksPage);

        SpotifyPage<PlaylistDto> playlistsPage = new SpotifyPage<>();
        playlistsPage.setItems(List.of(playlist("Small", 5), playlist("Big Jazz", 200), playlist("Medium", 40)));
        when(spotifyApi.getPlaylistsWithToken(eq("tok-123"), eq("spotify-abc"), anyInt(), anyInt()))
            .thenReturn(playlistsPage);

        stubAnthropicResponse(validJson());

        service.regenerate(user);

        verify(spotifyApi).getPlaylistsWithToken("tok-123", "spotify-abc", 0, 50);
        verify(jdbcTemplate).update(anyString(), eq(1L), anyString(), anyString(), anyString());
    }

    // ── refreshAllPlaceholders() ──────────────────────────────────────────────

    @Test
    void refreshAllPlaceholders_processesAllUsers() {
        AppUser user1 = new AppUser();
        user1.setId(1L);
        user1.setSpotifyId("user-1");
        AppUser user2 = new AppUser();
        user2.setId(2L);
        user2.setSpotifyId("user-2");
        when(userRepository.findAll()).thenReturn(List.of(user1, user2));
        OAuth2AuthorizedClient client = mockAuthorizedClient("tok-x");
        when(authorizedClientService.loadAuthorizedClient(eq("spotify"), anyString()))
            .thenReturn(client);
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        when(spotifyApi.getTopTracksWithToken(anyString(), anyString(), anyInt()))
            .thenReturn(page);
        stubAnthropicResponse(validJson());

        service.refreshAllPlaceholders();

        verify(jdbcTemplate, times(2)).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void refreshAllPlaceholders_continuesPastIndividualFailures() {
        AppUser user1 = new AppUser();
        user1.setId(1L);
        user1.setSpotifyId("user-1");
        AppUser user2 = new AppUser();
        user2.setId(2L);
        user2.setSpotifyId("user-2");
        when(userRepository.findAll()).thenReturn(List.of(user1, user2));

        // user-1 fails at token load
        when(authorizedClientService.loadAuthorizedClient("spotify", "user-1"))
            .thenThrow(new RuntimeException("DB error"));
        // user-2 succeeds
        OAuth2AuthorizedClient client2 = mockAuthorizedClient("tok-2");
        when(authorizedClientService.loadAuthorizedClient("spotify", "user-2"))
            .thenReturn(client2);
        SpotifyPage<TrackDto> page = new SpotifyPage<>();
        page.setItems(List.of());
        when(spotifyApi.getTopTracksWithToken(anyString(), anyString(), anyInt()))
            .thenReturn(page);
        stubAnthropicResponse(validJson());

        service.refreshAllPlaceholders();

        // user-2 was upserted despite user-1 failing
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any());
    }

    // ── getPlaceholder() ──────────────────────────────────────────────────────

    @Test
    void getPlaceholder_returnsStoredFields_whenPresent() {
        PlaceholderResponse row = new PlaceholderResponse(
            "something to listen to at 2am", "something like my Jazz playlist",
            "rain on train windows and something left unsaid", true);
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<PlaceholderResponse>>any(), eq("spotify-abc")))
            .thenReturn(List.of(row));

        PlaceholderResponse result = service.getPlaceholder("spotify-abc");

        assertThat(result.ready()).isTrue();
        assertThat(result.moodExample()).isEqualTo("something to listen to at 2am");
        assertThat(result.playlistExample()).isEqualTo("something like my Jazz playlist");
        assertThat(result.feelingLine()).isEqualTo("rain on train windows and something left unsaid");
    }

    @Test
    void getPlaceholder_returnsNotReady_whenNoRow() {
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<PlaceholderResponse>>any(), eq("spotify-abc")))
            .thenReturn(List.of());

        PlaceholderResponse result = service.getPlaceholder("spotify-abc");

        assertThat(result.ready()).isFalse();
        assertThat(result.moodExample()).isNull();
        assertThat(result.playlistExample()).isNull();
        assertThat(result.feelingLine()).isNull();
    }
}
