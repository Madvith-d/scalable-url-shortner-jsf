package com.shortify;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OwnershipMigrationIT extends RedisIntegrationSupport {

    @Autowired
    private DataSource dataSource;

    @Test
    void v4PreservesV3ClickHistoryAndAddsOnlyNullableCountryAndCity() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
            Long urlId = jdbc.queryForObject("INSERT INTO " + schema + ".short_urls "
                    + "(short_code, original_url, created_at, active) "
                    + "VALUES ('legacy', 'https://example.com', now(), TRUE) RETURNING id", Long.class);
            jdbc.update("INSERT INTO " + schema + ".click_events "
                    + "(short_url_id, accessed_at, referrer, device, geography) "
                    + "VALUES (?, '2026-01-02T03:04:05Z', 'example.com', 'Mobile', 'Legacy region')", urlId);
            var before = jdbc.queryForMap("SELECT * FROM " + schema + ".click_events");
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            var after = jdbc.queryForMap("SELECT * FROM " + schema + ".click_events");
            assertThat(after).containsAllEntriesOf(before).containsEntry("country_code", null).containsEntry("city", null);
            assertThat(after).hasSize(before.size() + 2);
            var columns = jdbc.queryForList("SELECT column_name, character_maximum_length, is_nullable "
                    + "FROM information_schema.columns WHERE table_schema = ? AND table_name = 'click_events' "
                    + "AND column_name IN ('country_code', 'city') ORDER BY column_name", schema);
            assertThat(columns).containsExactly(
                    java.util.Map.of("column_name", "city", "character_maximum_length", 128, "is_nullable", "YES"),
                    java.util.Map.of("column_name", "country_code", "character_maximum_length", 2, "is_nullable", "YES"));
            assertThat(jdbc.queryForObject("SELECT coalesce(country_code, 'Unknown') FROM " + schema + ".click_events",
                    String.class)).isEqualTo("Unknown");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v2PreservesV1UrlsAndAddsNullableOwnershipOnRealPostgreSql() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("1").load().migrate();
            jdbc.update("INSERT INTO " + schema + ".short_urls (short_code, original_url, created_at, expires_at, active) "
                    + "VALUES ('oldCode', 'https://example.com/old', '2020-01-01T00:00:00Z', NULL, TRUE)");
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            var rows = jdbc.queryForList("SELECT * FROM " + schema + ".short_urls");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).containsEntry("short_code", "oldCode")
                    .containsEntry("original_url", "https://example.com/old")
                    .containsEntry("active", true).containsEntry("user_id", null).containsEntry("expires_at", null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".users", Long.class)).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
