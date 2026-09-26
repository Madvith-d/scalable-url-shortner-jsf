package com.shortify.service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import com.shortify.dto.CreateShortUrlRequest;
import com.shortify.dto.ShortUrlPage;
import com.shortify.dto.ShortUrlResponse;
import com.shortify.entity.ShortUrl;
import com.shortify.exception.UrlException;
import com.shortify.repository.ShortUrlRepository;
import com.shortify.security.CurrentUser;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final CurrentUser currentUser;
    private final CustomAliasValidator aliases;
    private final Clock clock;
    private final String baseUrl;
    private final RedirectCache cache;
    private final ApplicationEventPublisher events;

    public ShortUrlService(ShortUrlRepository repository, ShortUrlWriter writer, ShortCodeGenerator generator,
                           OriginalUrlValidator validator, CurrentUser currentUser, CustomAliasValidator aliases, Clock clock,
                           @Value("${shortify.base-url}") String baseUrl, RedirectCache cache,
                           ApplicationEventPublisher events) {
        this.cache = cache;
        this.events = events;
        this.repository = repository;
        this.writer = writer;
        this.generator = generator;
        this.validator = validator;
        this.currentUser = currentUser;
        this.aliases = aliases;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ShortUrlResponse create(CreateShortUrlRequest request) {
        Long userId = currentUser.requireId();
        validator.validate(request.originalUrl());
        aliases.validate(request.customAlias());
        Instant expiresAt = parseExpiration(request.expiresAt());
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = request.customAlias() == null ? generator.generate() : request.customAlias();
            if (aliases.isReserved(code)) {
                continue;
            }
            try {
                return response(writer.insert(code, request.originalUrl(), expiresAt, userId));
            } catch (DataIntegrityViolationException exception) {
                if (!isCodeCollision(exception)) {
                    throw exception;
                }
                if (request.customAlias() != null) {
                    throw new UrlException(HttpStatus.CONFLICT, "ALIAS_IN_USE", "The custom alias is already in use.");
                }
            }
        }
        throw new UrlException(HttpStatus.SERVICE_UNAVAILABLE, "CODE_GENERATION_UNAVAILABLE",
                "Unable to allocate a short code. Please try again.");
    }

    public ShortUrlResponse get(Long id) {
        return response(requireOwned(id));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String resolve(String shortCode) {
        return resolveTarget(shortCode).destination();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RedirectCache.Target resolveTarget(String shortCode) {
        var target = cache.resolve(shortCode, () -> RedirectCache.Target.from(
                repository.findByShortCode(shortCode).orElseThrow(this::notFound)));
        if (!target.active()) {
            throw new UrlException(HttpStatus.GONE, "URL_INACTIVE", "The short URL is inactive.");
        }
        if (target.expiresAt() != null && !target.expiresAt().isAfter(clock.instant())) {
            throw new UrlException(HttpStatus.GONE, "URL_EXPIRED", "The short URL has expired.");
        }
        return target;
    }

    @Transactional
    public void deactivate(Long id) {
        ShortUrl url = requireOwned(id);
        url.deactivate();
        events.publishEvent(new RedirectCache.Changed(url.getShortCode()));
    }

    @Transactional
    public ShortUrlResponse update(Long id, boolean active) {
        ShortUrl url = requireOwned(id);
        url.setActive(active);
        events.publishEvent(new RedirectCache.Changed(url.getShortCode()));
        return response(url);
    }

    public ShortUrlPage list(int page, int size) {
        Long userId = currentUser.requireId();
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw new UrlException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "page must be 0-1000000 and size must be 1-100.");
        }
        var result = repository.findAllByUserId(userId, PageRequest.of(page, size, Sort.by("id").descending()));
        return new ShortUrlPage(result.getContent().stream().map(this::response).toList(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    public ShortUrl requireOwned(Long id) {
        return repository.findByIdAndUserId(id, currentUser.requireId()).orElseThrow(this::notFound);
    }

    private UrlException notFound() {
        return new UrlException(HttpStatus.NOT_FOUND, "URL_NOT_FOUND", "The short URL was not found.");
    }

    private Instant parseExpiration(String value) {
        if (value == null) {
            return null;
        }
        try {
            Instant expiration = Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
            if (expiration.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !expiration.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {
                throw new UrlException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                        "expiresAt must be within calendar years 0001 through 9999.");
            }
            return expiration;
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
