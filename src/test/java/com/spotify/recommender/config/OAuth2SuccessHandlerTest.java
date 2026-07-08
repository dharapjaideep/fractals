package com.spotify.recommender.config;

import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import com.spotify.recommender.service.EmbeddingService;
import com.spotify.recommender.service.PlaceholderService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OAuth2SuccessHandler}, the post-login success handler that upserts the
 * authenticated {@link AppUser} and then redirects.
 *
 * <p>The redirect target is asserted explicitly: a regression here changes where every user
 * lands after authenticating, so the auth-flow behavior must be pinned by a test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuth2SuccessHandlerTest {

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private PlaceholderService placeholderService;

    @Mock
    private EmbeddingService embeddingService;

    /**
     * Builds a {@link DefaultOAuth2User} carrying the attributes the handler reads,
     * with {@code "id"} as the Spotify id (the name attribute key).
     */
    private OAuth2User spotifyUser() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", "spotify-123");
        attributes.put("display_name", "Test User");
        attributes.put("email", "test@example.com");
        return new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "id");
    }

    private Authentication authFor(OAuth2User user) {
        return new TestingAuthenticationToken(user, null);
    }

    private OAuth2SuccessHandler handler() {
        return new OAuth2SuccessHandler(userRepository, placeholderService, embeddingService);
    }

    @Test
    void onAuthenticationSuccess_redirectsToRoot() throws Exception {
        // The default target URL was changed from /api/auth/me (raw JSON) to / (the SPA);
        // this is the regression-guard for that auth-flow change.
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationSuccess(request, response, authFor(spotifyUser()));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void onAuthenticationSuccess_upsertsUserFromPrincipalAttributes() throws Exception {
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.empty());

        handler().onAuthenticationSuccess(
            new MockHttpServletRequest(), new MockHttpServletResponse(), authFor(spotifyUser()));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getSpotifyId()).isEqualTo("spotify-123");
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Test User");
        assertThat(saved.getValue().getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void onAuthenticationSuccess_handlesNullEmailAttribute() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", "spotify-123");
        attributes.put("display_name", "Test User");
        // No email attribute — handler must not NPE.
        OAuth2User userWithoutEmail =
            new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "id");
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.empty());

        handler().onAuthenticationSuccess(
            new MockHttpServletRequest(), new MockHttpServletResponse(), authFor(userWithoutEmail));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isNull();
    }

    @Test
    void onAuthenticationSuccess_reusesExistingUser() throws Exception {
        AppUser existing = new AppUser();
        existing.setSpotifyId("spotify-123");
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.of(existing));

        handler().onAuthenticationSuccess(
            new MockHttpServletRequest(), new MockHttpServletResponse(), authFor(spotifyUser()));

        // The existing row is updated (display name refreshed), not duplicated.
        verify(userRepository).save(existing);
        assertThat(existing.getDisplayName()).isEqualTo("Test User");
    }

    @Test
    void onAuthenticationSuccess_triggersPlaceholderRegeneration_forNewUser() throws Exception {
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.empty());

        handler().onAuthenticationSuccess(
            new MockHttpServletRequest(), new MockHttpServletResponse(), authFor(spotifyUser()));

        verify(placeholderService).regenerateAsync(any());
    }

    @Test
    void onAuthenticationSuccess_triggersPlaceholderRegeneration_forExistingUserEveryLogin()
            throws Exception {
        // Unconditional on every login, not gated on prior state — user_chat_placeholder_preferences is
        // upserted each time so personalization stays fresh as the user's taste evolves.
        AppUser existing = new AppUser();
        existing.setSpotifyId("spotify-123");
        existing.setId(42L);
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.of(existing));

        handler().onAuthenticationSuccess(
            new MockHttpServletRequest(), new MockHttpServletResponse(), authFor(spotifyUser()));

        verify(placeholderService).regenerateAsync(existing);
    }

    @Test
    @Disabled("RAG embedding trigger disabled in OAuth2SuccessHandler — re-enable when privacy review complete and embedding re-enabled")
    void onAuthenticationSuccess_triggersAsyncPlaceholderAndEmbedding_whenPlaceholderTextIsNull()
            throws Exception {
        AppUser existing = new AppUser();
        existing.setSpotifyId("spotify-123");
        existing.setId(42L);
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.of(existing));

        handler().onAuthenticationSuccess(
            new MockHttpServletRequest(), new MockHttpServletResponse(), authFor(spotifyUser()));

        verify(placeholderService).regenerateAsync(any());
        verify(embeddingService).buildLibraryAsync(any());
    }

    @Test
    @Disabled("RAG embedding trigger disabled in OAuth2SuccessHandler — re-enable when privacy review complete and embedding re-enabled")
    void onAuthenticationSuccess_triggersEmbeddingOnly_whenUserLacksEmbeddings() throws Exception {
        AppUser existing = new AppUser();
        existing.setSpotifyId("spotify-123");
        existing.setId(42L);
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.of(existing));
        when(embeddingService.hasEmbeddings(42L)).thenReturn(false);

        handler().onAuthenticationSuccess(
            new MockHttpServletRequest(), new MockHttpServletResponse(), authFor(spotifyUser()));

        verify(placeholderService, never()).regenerateAsync(any());
        verify(embeddingService).buildLibraryAsync(any());
    }

    @Test
    void onAuthenticationSuccess_doesNotTriggerEmbedding_whenUserAlreadyHasEmbeddings()
            throws Exception {
        AppUser existing = new AppUser();
        existing.setSpotifyId("spotify-123");
        existing.setId(42L);
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.of(existing));
        when(embeddingService.hasEmbeddings(42L)).thenReturn(true);

        handler().onAuthenticationSuccess(
            new MockHttpServletRequest(), new MockHttpServletResponse(), authFor(spotifyUser()));

        verify(embeddingService, never()).buildLibraryAsync(any());
    }
}
