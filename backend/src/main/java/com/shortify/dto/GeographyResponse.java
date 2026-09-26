package com.shortify.dto;

import java.util.List;

public record GeographyResponse(long totalClicks, List<AnalyticsResponse.Bucket> buckets) { }
