package com.spotify.recommender.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotify.recommender.config.OAuth2SuccessHandler;
import com.spotify.recommender.model.dto.chat.ChatRequest;
import com.spotify.recommender.model.dto.chat.ChatResponse;
import com.spotify.recommender.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
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
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatService chatService;

    // SecurityConfig constructor-injects this; provide a mock so the filter chain can be built
    @MockBean
    private OAuth2SuccessHandler successHandler;

    // ── POST /api/chat ────────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void chat_returns200_withValidRequest() throws Exception {
        ChatResponse chatResponse = new ChatResponse("Here are some tracks!", null, List.of(), null, null);
        when(chatService.chat(any(ChatRequest.class), anyString())).thenReturn(chatResponse);

        mockMvc.perform(post("/api/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"give me chill songs\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Here are some tracks!"))
            .andExpect(jsonPath("$.tracks").isArray());

        verify(chatService).chat(any(ChatRequest.class), anyString());
    }

    @Test
    @WithMockUser
    void chat_returns400_whenMessageBlank() throws Exception {
        mockMvc.perform(post("/api/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void chat_returns400_whenMessageTooLong() throws Exception {
        String longMessage = "a".repeat(501);
        mockMvc.perform(post("/api/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"" + longMessage + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void chat_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/api/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"hi\"}"))
            .andExpect(status().is3xxRedirection());
    }
}