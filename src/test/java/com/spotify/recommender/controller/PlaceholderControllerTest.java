package com.spotify.recommender.controller;

import com.spotify.recommender.config.OAuth2SuccessHandler;
import com.spotify.recommender.service.PlaceholderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.spotify.recommender.model.dto.placeholder.PlaceholderResponse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link PlaceholderController} ({@code GET /api/chat/placeholder}).
 */
@WebMvcTest(PlaceholderController.class)
@TestPropertySource(properties = {
    "spring.security.oauth2.client.registration.spotify.client-id=test-id",
    "spring.security.oauth2.client.registration.spotify.client-secret=test-secret",
    "spring.security.oauth2.client.registration.spotify.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.spotify.redirect-uri=http://localhost/cb",
    "spring.security.oauth2.client.registration.spotify.scope=openid",
    "spring.security.oauth2.client.provider.spotify.authorization-uri=https://accounts.spotify.com/authorize",
    "spring.security.oauth2.client.provider.spotify.token-uri=https://accounts.spotify.com/api/token",
    "spring.security.oauth2.client.provider.spotify.user-info-uri=https://api.spotify.com/v1/me",
    "spring.security.oauth2.client.provider.spotify.user-name-attribute=id"
})
class PlaceholderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlaceholderService placeholderService;

    // SecurityConfig constructor-injects this; provide a mock so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    @Test
    @WithMockUser
    void getPlaceholder_returnsPersonalizedText_whenAuthenticated() throws Exception {
        when(placeholderService.getPlaceholder(anyString()))
            .thenReturn(new PlaceholderResponse(
                "something to listen to at 2am",
                "something like my Jazz playlist",
                "Late drives with windows down",
                true));

        mockMvc.perform(get("/api/chat/placeholder"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ready").value(true))
            .andExpect(jsonPath("$.moodExample").value("something to listen to at 2am"))
            .andExpect(jsonPath("$.playlistExample").value("something like my Jazz playlist"))
            .andExpect(jsonPath("$.feelingLine").value("Late drives with windows down"));
    }

    @Test
    @WithMockUser
    void getPlaceholder_returnsNotReady_whenNoPlaceholderStored() throws Exception {
        when(placeholderService.getPlaceholder(anyString()))
            .thenReturn(new PlaceholderResponse(null, null, null, false));

        mockMvc.perform(get("/api/chat/placeholder"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ready").value(false));
    }

    @Test
    void getPlaceholder_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(get("/api/chat/placeholder"))
            .andExpect(status().is3xxRedirection());
    }
}
