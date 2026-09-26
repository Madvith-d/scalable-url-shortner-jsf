package com.shortify.repository;

import java.util.List;

import com.shortify.entity.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    interface Bucket {
        String getLabel();
        long getClicks();
    }

    long countByShortUrlId(Long id);

    @Query(value = """
            SELECT to_char(accessed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS label, count(*) AS clicks
            FROM click_events WHERE short_url_id = :id GROUP BY label ORDER BY label
            """, nativeQuery = true)
    List<Bucket> clicksOverTime(Long id);

    @Query(value = """
            SELECT referrer AS label, count(*) AS clicks FROM click_events
            WHERE short_url_id = :id GROUP BY referrer ORDER BY clicks DESC, label
            """, nativeQuery = true)
    List<Bucket> referrers(Long id);

    @Query(value = """
            SELECT device AS label, count(*) AS clicks FROM click_events
            WHERE short_url_id = :id GROUP BY device ORDER BY clicks DESC, label
            """, nativeQuery = true)
    List<Bucket> devices(Long id);

    @Query(value = """
            SELECT coalesce(country_code, 'Unknown') AS label, count(*) AS clicks FROM click_events
            WHERE short_url_id = :id GROUP BY label ORDER BY clicks DESC, label
            """, nativeQuery = true)
    List<Bucket> geography(Long id);

    @Query(value = """
            SELECT coalesce(country_code, 'Unknown') AS label, count(*) AS clicks FROM click_events
            WHERE short_url_id = :id GROUP BY label ORDER BY clicks DESC, label LIMIT 10
            """, nativeQuery = true)
    List<Bucket> topCountries(Long id);

    @Query(value = """
            SELECT CASE WHEN city IS NULL THEN 'Unknown'
                        ELSE city || ', ' || coalesce(country_code, 'Unknown') END AS label,
                   count(*) AS clicks FROM click_events
            WHERE short_url_id = :id GROUP BY label ORDER BY clicks DESC, label LIMIT 10
            """, nativeQuery = true)
    List<Bucket> topCities(Long id);
}
