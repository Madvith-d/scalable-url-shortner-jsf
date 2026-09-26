package com.shortify.service;

import java.time.Clock;

import com.shortify.repository.ShortUrlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpiryCleanup {

    private final ShortUrlRepository urls;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int batchSize;

    public ExpiryCleanup(ShortUrlRepository urls, ApplicationEventPublisher events, Clock clock,
                         @Value("${shortify.cleanup.batch-size}") int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("Cleanup batch size must be 1-10000.");
        }
        this.urls = urls;
        this.events = events;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${shortify.cleanup.interval}", initialDelayString = "${shortify.cleanup.interval}")
    @Transactional
    public void clean() {
        for (var url : urls.lockExpiredBatch(clock.instant(), batchSize)) {
            url.deactivate();
            events.publishEvent(new RedirectCache.Changed(url.getShortCode()));
        }
    }
}
