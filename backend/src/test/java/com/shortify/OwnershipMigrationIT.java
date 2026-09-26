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
