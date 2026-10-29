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

    long countByShortUrlIdAndVisitorHashIsNotNull(Long id);

    @Query("select count(distinct c.visitorHash) from ClickEvent c where c.shortUrlId = :id")
    long countUniqueVisitors(Long id);

    @Query(value = """
            SELECT to_char(accessed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS label, count(*) AS clicks
            FROM click_events WHERE short_url_id = :id GROUP BY label ORDER BY label
            """, nativeQuery = true)
    List<Bucket> clicksOverTime(Long id);

    @Query(value = """
            SELECT to_char(accessed_at AT TIME ZONE 'UTC', 'HH24') AS label, count(*) AS clicks
            FROM click_events WHERE short_url_id = :id GROUP BY label ORDER BY label
            """, nativeQuery = true)
    List<Bucket> clicksByHour(Long id);

    @Query(value = """
            SELECT CASE EXTRACT(ISODOW FROM accessed_at AT TIME ZONE 'UTC')
                WHEN 1 THEN 'Mon' WHEN 2 THEN 'Tue' WHEN 3 THEN 'Wed' WHEN 4 THEN 'Thu'
                WHEN 5 THEN 'Fri' WHEN 6 THEN 'Sat' ELSE 'Sun' END AS label, count(*) AS clicks
            FROM click_events WHERE short_url_id = :id
            GROUP BY EXTRACT(ISODOW FROM accessed_at AT TIME ZONE 'UTC')
            ORDER BY EXTRACT(ISODOW FROM accessed_at AT TIME ZONE 'UTC')
            """, nativeQuery = true)
    List<Bucket> clicksByWeekday(Long id);

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

}
