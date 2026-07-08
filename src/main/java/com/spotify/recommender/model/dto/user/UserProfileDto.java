package com.spotify.recommender.model.dto.user;

import com.spotify.recommender.model.entity.AppUser;

public record UserProfileDto(String spotifyId, String displayName, String email) {

    public static UserProfileDto from(AppUser user) {
        return new UserProfileDto(user.getSpotifyId(), user.getDisplayName(), user.getEmail());
    }
}