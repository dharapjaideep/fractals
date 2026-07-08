package com.spotify.recommender.repository;

import com.spotify.recommender.model.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findBySpotifyId(String spotifyId);
}
