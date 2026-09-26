package com.shortify;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import com.shortify.entity.ShortUrl;
import com.shortify.service.ShortUrlService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@Rollback
class ShortUrlPersistenceIT {

    @Autowired
    private ShortUrlService service;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void persistsAndRetrievesThroughJpaOnPostgreSql() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        String shortCode = newShortCode();
        Instant beforeSave = Instant.now().minusSeconds(1);
        Instant expiresAt = Instant.parse("2030-01-01T12:00:00Z");
        ShortUrl saved = service.save(new ShortUrl(shortCode, "https://example.com/long/path", expiresAt, true));
        entityManager.flush();
        entityManager.clear();

        ShortUrl retrieved = service.findById(saved.getId()).orElseThrow();
        assertThat(retrieved).isNotSameAs(saved);
        assertThat(retrieved.getId()).isPositive();
        assertThat(retrieved.getShortCode()).isEqualTo(shortCode);
        assertThat(retrieved.getOriginalUrl()).isEqualTo("https://example.com/long/path");
        assertThat(retrieved.getCreatedAt()).isBetween(beforeSave, Instant.now().plusSeconds(1));
        assertThat(retrieved.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(retrieved.isActive()).isTrue();

        entityManager.clear();
        assertThat(service.findByShortCode(shortCode)).hasValueSatisfying(found ->
                assertThat(found.getId()).isEqualTo(saved.getId()));
    }

    @Test
    void persistsOptionalExpirationAndExplicitInactiveStatus() {
        ShortUrl saved = service.save(new ShortUrl(newShortCode(), "https://example.com", null, false));
        entityManager.flush();
        entityManager.clear();

        ShortUrl retrieved = service.findById(saved.getId()).orElseThrow();
        assertThat(retrieved.getExpiresAt()).isNull();
        assertThat(retrieved.isActive()).isFalse();
    }

    @Test
    void rejectsDuplicateShortCodesInPostgreSql() {
        String shortCode = newShortCode();
        service.save(new ShortUrl(shortCode, "https://example.com/first", null, true));
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> service.save(
                new ShortUrl(shortCode, "https://example.com/second", null, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void databaseRejectsNullRequiredColumns(int nullColumn) {
        Object[] values = {newShortCode(), "https://example.com", Instant.now().toString(), true};
        values[nullColumn] = null;

        // Direct SQL verifies database constraints independently of Bean Validation.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO short_urls (short_code, original_url, created_at, active)
                VALUES (?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE), ?)
                """, values))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void returnsEmptyForUnknownShortCode() {
        assertThat(service.findByShortCode(newShortCode())).isEmpty();
    }

    private String newShortCode() {
        return UUID.randomUUID().toString();
    }
}
