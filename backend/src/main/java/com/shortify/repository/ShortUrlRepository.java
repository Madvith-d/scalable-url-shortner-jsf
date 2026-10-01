package com.shortify.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.shortify.entity.ShortUrl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    @Query(value = """
            SELECT * FROM short_urls WHERE active = true AND expires_at <= :now
            ORDER BY expires_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ShortUrl> lockExpiredBatch(Instant now, int batchSize);

    @Modifying
    @Query(value = """
            UPDATE short_urls SET click_count = click_count + 1
            WHERE id = :id AND active = true
              AND (activates_at IS NULL OR activates_at <= :now)
              AND (expires_at IS NULL OR expires_at > :now)
              AND (max_clicks IS NULL OR click_count < max_clicks)
            """, nativeQuery = true)
    int admitClick(Long id, Instant now);

    Optional<ShortUrl> findByShortCode(String shortCode);

    Optional<ShortUrl> findByIdAndUserId(Long id, Long userId);

    Page<ShortUrl> findAllByUserId(Long userId, Pageable pageable);
}
