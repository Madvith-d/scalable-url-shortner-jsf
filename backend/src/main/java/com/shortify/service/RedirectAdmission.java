package com.shortify.service;

import java.time.Clock;
import java.time.Instant;

import com.shortify.entity.ShortUrl;
import com.shortify.exception.UrlException;
import com.shortify.repository.ShortUrlRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL is authoritative even when Redis or best-effort analytics are unavailable. */
@Service
public class RedirectAdmission {
    private final ShortUrlRepository urls;
    private final Clock clock;

    public RedirectAdmission(ShortUrlRepository urls, Clock clock) {
        this.urls = urls;
        this.clock = clock;
    }

    @Transactional
    public void admit(Long id, boolean countClick) {
        Instant now = clock.instant();
        if (countClick && urls.admitClick(id, now) == 1) return;
        ShortUrl url = urls.findById(id).orElseThrow(() ->
                new UrlException(HttpStatus.NOT_FOUND, "URL_NOT_FOUND", "The short URL was not found."));
        if (!url.isActive()) {
            throw new UrlException(HttpStatus.GONE, "URL_INACTIVE", "The short URL is inactive.");
        }
        if (url.getExpiresAt() != null && !url.getExpiresAt().isAfter(now)) {
            throw new UrlException(HttpStatus.GONE, "URL_EXPIRED", "The short URL has expired.");
        }
        if (url.getActivatesAt() != null && url.getActivatesAt().isAfter(now)) {
            throw new UrlException(HttpStatus.NOT_FOUND, "URL_NOT_ACTIVE_YET", "The short URL is scheduled and not active yet.");
        }
        if (url.getMaxClicks() != null && url.getClickCount() >= url.getMaxClicks()) {
            throw new UrlException(HttpStatus.GONE, "URL_CLICK_CAP_REACHED", "The short URL has reached its click cap.");
        }
        // A state change between a failed UPDATE and this read must not grant an uncounted GET.
        if (countClick) {
            throw new UrlException(HttpStatus.CONFLICT, "URL_STATE_CHANGED", "Link availability changed. Please try again.");
        }
    }
}
