package com.shortify.dto;

import java.util.List;

public record AnalyticsResponse(long totalClicks, long uniqueClicks, List<Day> clicksOverTime, List<Bucket> referrers,
                                List<Bucket> devices) {
    public record Day(String date, long clicks) { }
    public record Bucket(String label, long clicks) { }
}
