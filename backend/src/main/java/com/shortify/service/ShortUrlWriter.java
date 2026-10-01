package com.shortify.service;

import java.time.Instant;

import com.shortify.entity.ShortUrl;
import com.shortify.repository.ShortUrlRepository;
import com.shortify.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortUrlWriter {

    private final ShortUrlRepository repository;
    private final UserRepository users;

    public ShortUrlWriter(ShortUrlRepository repository, UserRepository users) {
        this.repository = repository;
        this.users = users;
    }

    // Each failed insert must roll back before another code can be attempted on PostgreSQL.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShortUrl insert(String shortCode, String originalUrl, Instant expiresAt, Long userId) {
        return insert(shortCode, originalUrl, expiresAt, userId, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShortUrl insert(String shortCode, String originalUrl, Instant expiresAt, Long userId,
                           Instant activatesAt, Long maxClicks) {
        return repository.saveAndFlush(new ShortUrl(shortCode, originalUrl, expiresAt,
                users.getReferenceById(userId), activatesAt, maxClicks));
    }
}
