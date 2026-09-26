package com.shortify.dto;

import java.util.List;

public record ShortUrlPage(List<ShortUrlResponse> content, int page, int size, long totalElements, int totalPages) {
}
