package com.spotify.recommender.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InviteOnlyAuthenticationFailureHandler}, which redirects to the
 * branded invite-only page whenever OAuth2 login fails (most commonly Spotify's
 * {@code error=access_denied} for users not on the app's allowlist).
 */
@ExtendWith(MockitoExtension.class)
class InviteOnlyAuthenticationFailureHandlerTest {

    @InjectMocks
    private InviteOnlyAuthenticationFailureHandler handler;

    @Test
    void onAuthenticationFailure_redirectsToInviteOnlyPage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new BadCredentialsException("access_denied");

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/invite-only.html");
    }
}
