package com.spotify.recommender.service;

import com.spotify.recommender.model.entity.AppUser;
import com.spotify.recommender.repository.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service for looking up {@link AppUser} records by their Spotify user ID.
 * Throws a 404 response exception when no matching user is found, so callers
 * never receive a null and controllers stay thin.
 */
@Service
public class UserService {

    private final AppUserRepository userRepository;

    public UserService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns the {@link AppUser} with the given Spotify user ID.
     *
     * @param spotifyId the Spotify user ID (from the OAuth2 token subject)
     * @return the matching {@link AppUser}
     * @throws org.springframework.web.server.ResponseStatusException HTTP 404 if no user exists
     */
    public AppUser findBySpotifyId(String spotifyId) {
        return userRepository.findBySpotifyId(spotifyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
