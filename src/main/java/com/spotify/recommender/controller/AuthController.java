package com.spotify.recommender.controller;

import com.spotify.recommender.model.dto.user.UserProfileDto;
import com.spotify.recommender.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> me(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(UserProfileDto.from(userService.findBySpotifyId(token.getName())));
    }

    // POST /api/auth/logout is handled by Spring Security (SecurityConfig.logout())
}
