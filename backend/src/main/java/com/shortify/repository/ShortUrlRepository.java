package com.shortify.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.shortify.entity.ShortUrl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    @Query(value = """
            SELECT * FROM short_urls WHERE active = true AND expires_at <= :now
            ORDER BY expires_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ShortUrl> lockExpiredBatch(Instant now, int batchSize);

    Optional<ShortUrl> findByShortCode(String shortCode);

    Optional<ShortUrl> findByIdAndUserId(Long id, Long userId);

    Page<ShortUrl> findAllByUserId(Long userId, Pageable pageable);
}
