package com.spotify.recommender.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link SpotifyLogoutHandler}, which removes the Spotify OAuth2 authorized client
 * from the database on logout to prevent token reuse after session invalidation.
 */
@ExtendWith(MockitoExtension.class)
class SpotifyLogoutHandlerTest {

    @Mock
    private OAuth2AuthorizedClientService authorizedClientService;

    @InjectMocks
    private SpotifyLogoutHandler handler;

    @Test
    void logout_removesAuthorizedClientForAuthenticatedUser() {
        Authentication auth = new TestingAuthenticationToken("spotify-123", null);

        handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(), auth);

        verify(authorizedClientService).removeAuthorizedClient("spotify", "spotify-123");
    }

    @Test
    void logout_doesNothingWhenAuthenticationIsNull() {
        handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(), null);

        verify(authorizedClientService, never()).removeAuthorizedClient(any(), any());
    }

    @Test
    void logout_doesNotThrowWhenRemoveAuthorizedClientFails() {
        Authentication auth = new TestingAuthenticationToken("spotify-123", null);
        doThrow(new RuntimeException("DB error"))
            .when(authorizedClientService).removeAuthorizedClient("spotify", "spotify-123");

        // Must not propagate — fail-safe: DB error must not block session invalidation.
        handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(), auth);
    }

    private static String any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
