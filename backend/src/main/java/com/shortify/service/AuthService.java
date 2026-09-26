package com.shortify.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import com.shortify.dto.AuthRequest;
import com.shortify.dto.AuthResponse;
import com.shortify.entity.User;
import com.shortify.exception.UrlException;
import com.shortify.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final long expiresIn;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtEncoder encoder, Clock clock,
                       @Value("${shortify.jwt.issuer}") String issuer,
                       @Value("${shortify.jwt.expires-in}") long expiresIn) {
        if (expiresIn < 1) {
            throw new IllegalArgumentException("JWT_EXPIRES_IN must be positive.");
        }
        this.users = users;
        this.passwords = passwords;
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.expiresIn = expiresIn;
        this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    public AuthResponse register(AuthRequest request) {
        validatePasswordBytes(request.password());
        User user;
        try {
            user = users.saveAndFlush(new User(request.email(), passwords.encode(request.password())));
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState()) && "uk_users_email".equals(violation.getConstraintName())) {
                    throw new UrlException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "An account with this email already exists.");
                }
            }
            throw exception;
        }
        return token(user);
    }

    public AuthResponse login(AuthRequest request) {
        validatePasswordBytes(request.password());
        User user = users.findByEmail(request.email()).orElse(null);
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches) {
            throw new UrlException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "The email or password is incorrect.");
        }
        return token(user);
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new UrlException(HttpStatus.BAD_REQUEST, "INVALID_BODY", "Password must not exceed 72 UTF-8 bytes.");
        }
    }

    private AuthResponse token(User user) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(user.getId().toString())
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(expiresIn)).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new AuthResponse(token, "Bearer", expiresIn, user.getEmail());
    }
}
