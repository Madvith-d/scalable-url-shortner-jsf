package com.shortify.service;

import java.time.Instant;

import com.shortify.entity.ShortUrl;
import com.shortify.repository.ShortUrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortUrlWriter {

    private final ShortUrlRepository repository;

    public ShortUrlWriter(ShortUrlRepository repository) {
        this.repository = repository;
    }

    // Each failed insert must roll back before another code can be attempted on PostgreSQL.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShortUrl insert(String shortCode, String originalUrl, Instant expiresAt) {
        return repository.saveAndFlush(new ShortUrl(shortCode, originalUrl, expiresAt, true));
    }
}
