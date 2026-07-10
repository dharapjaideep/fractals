package com.spotify.recommender.service;

import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Tests for {@link UserService}, in particular the fail-closed 404 behaviour
 * of {@link UserService#findBySpotifyId(String)} (flagged as an untested
 * security-relevant path in the 2026-07-09 code review, finding H4).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private AppUserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
    }

    @Test
    void findBySpotifyId_userExists_returnsUser() {
        AppUser user = new AppUser();
        user.setSpotifyId("spotify123");
        user.setDisplayName("Jaideep");
        user.setEmail("jaideep@example.com");
        when(userRepository.findBySpotifyId("spotify123")).thenReturn(Optional.of(user));

        AppUser result = userService.findBySpotifyId("spotify123");

        assertThat(result).isSameAs(user);
    }

    @Test
    void findBySpotifyId_userMissing_throws404() {
        when(userRepository.findBySpotifyId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findBySpotifyId("unknown"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(NOT_FOUND));
    }
}
