package com.spotify.recommender.service;

import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService}, covering both branches of the fail-closed lookup:
 * a present user is returned, and a missing user raises a 404 {@link ResponseStatusException}
 * rather than returning null (security-relevant — callers never receive a null identity).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private AppUserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void findBySpotifyId_returnsUser_whenPresent() {
        AppUser user = new AppUser();
        user.setSpotifyId("spotify-123");
        user.setDisplayName("Jay");
        when(userRepository.findBySpotifyId("spotify-123")).thenReturn(Optional.of(user));

        AppUser result = userService.findBySpotifyId("spotify-123");

        assertThat(result).isSameAs(user);
    }

    @Test
    void findBySpotifyId_throws404_whenMissing() {
        when(userRepository.findBySpotifyId("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findBySpotifyId("nobody"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
