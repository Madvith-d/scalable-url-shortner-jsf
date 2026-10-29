package com.shortify.dto;

import java.util.List;

public record AnalyticsResponse(long totalClicks, long uniqueClicks, long trackedClicks, List<Day> clicksOverTime,
                                List<Bucket> hourlyClicks, List<Bucket> weekdayClicks,
                                List<Bucket> referrers, List<Bucket> devices) {
    public record Day(String date, long clicks) { }
    public record Bucket(String label, long clicks) { }
}
