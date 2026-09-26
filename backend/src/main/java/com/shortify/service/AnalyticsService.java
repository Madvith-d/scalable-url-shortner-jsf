package com.shortify.service;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

import com.shortify.dto.AnalyticsResponse;
import com.shortify.entity.ClickEvent;
import com.shortify.repository.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private final ClickEventRepository clicks;
    private final ShortUrlService urls;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;
    private final LongAdder dropped = new LongAdder();

    public AnalyticsService(ClickEventRepository clicks, ShortUrlService urls,
                            @Qualifier("analyticsExecutor") ThreadPoolTaskExecutor executor, Clock clock) {
        this.clicks = clicks;
        this.urls = urls;
        this.executor = executor;
        this.clock = clock;
    }

    public void record(Long id, String referrer, String userAgent) {
        ClickEvent event = new ClickEvent(id, clock.instant(), referrerHost(referrer), device(userAgent), "Unknown");
        try {
            executor.execute(() -> {
                try {
                    clicks.save(event);
                } catch (RuntimeException exception) {
                    drop();
                }
            });
        } catch (TaskRejectedException exception) {
            drop();
        }
    }

    public long droppedCount() {
        return dropped.sum();
    }

    private void drop() {
        dropped.increment();
        long count = dropped.sum();
        if (count == 1 || count % 100 == 0) {
            log.warn("Analytics event dropped: queue full, shutdown, or persistence failure (dropped={})", count);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalyticsResponse get(Long id) {
        urls.requireOwned(id);
        return new AnalyticsResponse(clicks.countByShortUrlId(id), clicks.clicksOverTime(id).stream()
                .map(row -> new AnalyticsResponse.Day(row.getLabel(), row.getClicks())).toList(),
                buckets(clicks.referrers(id)), buckets(clicks.devices(id)), buckets(clicks.geography(id)));
    }

    private List<AnalyticsResponse.Bucket> buckets(List<ClickEventRepository.Bucket> rows) {
        return rows.stream().map(row -> new AnalyticsResponse.Bucket(row.getLabel(), row.getClicks())).toList();
    }

    static String referrerHost(String value) {
        if (value == null || value.isBlank()) {
            return "Direct";
        }
        if (value.length() > 8192) {
            return "Unknown";
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || host == null || host.length() > 253) {
                return "Unknown";
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return "Unknown";
        }
    }

    static String device(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        String agent = value.substring(0, Math.min(value.length(), 1024)).toLowerCase(Locale.ROOT);
        if (agent.contains("bot") || agent.contains("spider") || agent.contains("crawler")) {
            return "Bot";
        }
        if (agent.contains("ipad") || agent.contains("tablet")) {
            return "Tablet";
        }
        if (agent.contains("mobile") || agent.contains("iphone") || agent.contains("android")) {
            return "Mobile";
        }
        return "Desktop";
    }
}
