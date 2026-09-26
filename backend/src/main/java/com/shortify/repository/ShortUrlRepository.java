package com.shortify.repository;

import java.util.Optional;

import com.shortify.entity.ShortUrl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCode(String shortCode);

    Optional<ShortUrl> findByIdAndUserId(Long id, Long userId);

    Page<ShortUrl> findAllByUserId(Long userId, Pageable pageable);
}
