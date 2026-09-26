package com.shortify.service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.shortify.dto.CreateShortUrlRequest;
import com.shortify.dto.ShortUrlResponse;
import com.shortify.entity.ShortUrl;
import com.shortify.exception.UrlException;
import com.shortify.repository.ShortUrlRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ShortUrlService {

    static final int MAX_ATTEMPTS = 10;
    private final ShortUrlRepository repository;
    private final ShortUrlWriter writer;
    private final ShortCodeGenerator generator;
    private final OriginalUrlValidator validator;
    private final Clock clock;
    private final String baseUrl;

    public ShortUrlService(ShortUrlRepository repository, ShortUrlWriter writer, ShortCodeGenerator generator,
                           OriginalUrlValidator validator, Clock clock,
                           @Value("${shortify.base-url}") String baseUrl) {
        this.repository = repository;
        this.writer = writer;
        this.generator = generator;
        this.validator = validator;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ShortUrlResponse create(CreateShortUrlRequest request) {
        validator.validate(request.originalUrl());
        Instant expiresAt = parseExpiration(request.expiresAt());
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return response(writer.insert(generator.generate(), request.originalUrl(), expiresAt));
            } catch (DataIntegrityViolationException exception) {
                if (!isCodeCollision(exception)) {
                    throw exception;
                }
            }
        }
        throw new UrlException(HttpStatus.SERVICE_UNAVAILABLE, "CODE_GENERATION_UNAVAILABLE",
                "Unable to allocate a short code. Please try again.");
    }

    public ShortUrlResponse get(Long id) {
        return response(requireById(id));
    }

    public String resolve(String shortCode) {
        ShortUrl shortUrl = repository.findByShortCode(shortCode).orElseThrow(this::notFound);
        if (!shortUrl.isActive()) {
            throw new UrlException(HttpStatus.GONE, "URL_INACTIVE", "The short URL is inactive.");
        }
        if (shortUrl.getExpiresAt() != null && !shortUrl.getExpiresAt().isAfter(clock.instant())) {
            throw new UrlException(HttpStatus.GONE, "URL_EXPIRED", "The short URL has expired.");
        }
        return shortUrl.getOriginalUrl();
    }

    @Transactional
    public void deactivate(Long id) {
        requireById(id).deactivate();
    }

    @Transactional
    public ShortUrl save(ShortUrl shortUrl) {
        return repository.save(shortUrl);
    }

    public Optional<ShortUrl> findById(Long id) {
        return repository.findById(id);
    }

    public Optional<ShortUrl> findByShortCode(String shortCode) {
        return repository.findByShortCode(shortCode);
    }

    private ShortUrl requireById(Long id) {
        return repository.findById(id).orElseThrow(this::notFound);
    }

    private UrlException notFound() {
        return new UrlException(HttpStatus.NOT_FOUND, "URL_NOT_FOUND", "The short URL was not found.");
    }

    private Instant parseExpiration(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException exception) {
            throw new UrlException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "expiresAt must be an ISO-8601 timestamp with an offset.");
        }
    }

    private boolean isCodeCollision(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && "uk_short_urls_short_code".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private ShortUrlResponse response(ShortUrl url) {
        return new ShortUrlResponse(url.getId(), url.getShortCode(), baseUrl + "/" + url.getShortCode(),
                url.getOriginalUrl(), url.getCreatedAt(), url.getExpiresAt(), url.isActive());
    }
}
