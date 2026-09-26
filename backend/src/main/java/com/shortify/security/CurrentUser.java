package com.shortify.security;

import com.shortify.exception.UrlException;
import com.shortify.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public Long requireId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            try {
                Long id = Long.valueOf(token.getToken().getSubject());
                if (users.existsById(id)) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // A subject must identify a persisted account.
            }
        }
        throw new UrlException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
    }
}
