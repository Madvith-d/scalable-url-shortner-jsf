package com.shortify.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.shortify.dto.CreateShortUrlRequest;
import com.shortify.dto.ShortUrlResponse;
import com.shortify.entity.ShortUrl;
import com.shortify.exception.UrlException;
import com.shortify.repository.ShortUrlRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShortUrlServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final String ORIGINAL = "https://example.com/long/path";
    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final ShortUrlWriter writer = mock(ShortUrlWriter.class);
    private final ShortCodeGenerator generator = mock(ShortCodeGenerator.class);
    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        service = new ShortUrlService(repository, writer, generator, new OriginalUrlValidator(),
                Clock.fixed(NOW, ZoneOffset.UTC), "https://sho.rt/");
    }

    @Test
    void createsActiveUrlAndMapsToDto() {
        when(generator.generate()).thenReturn("aB12cD34");
        when(writer.insert("aB12cD34", ORIGINAL, null)).thenReturn(url(null, true));
        ShortUrlResponse result = service.create(new CreateShortUrlRequest(ORIGINAL, null));
        assertThat(result.shortCode()).isEqualTo("aB12cD34");
        assertThat(result.shortUrl()).isEqualTo("https://sho.rt/aB12cD34");
        assertThat(result.originalUrl()).isEqualTo(ORIGINAL);
        assertThat(result.active()).isTrue();
        assertThat(result.expiresAt()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void passesOptionalExpirationToWriter() {
        when(generator.generate()).thenReturn("aB12cD34");
        when(writer.insert("aB12cD34", ORIGINAL, NOW)).thenReturn(url(NOW, true));
        assertThat(service.create(new CreateShortUrlRequest(ORIGINAL, NOW.toString())).expiresAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsInvalidUrlBeforeAllocatingCode() {
        assertError(() -> service.create(new CreateShortUrlRequest("ftp://example.com", null)),
                HttpStatus.BAD_REQUEST, "INVALID_URL");
        verifyNoInteractions(generator, writer, repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "tomorrow", "2026-09-26", "2026-09-26T12:00:00", "2026-13-01T00:00:00Z"})
    void rejectsMalformedExpirationBeforeWriting(String expiresAt) {
        assertError(() -> service.create(new CreateShortUrlRequest(ORIGINAL, expiresAt)),
                HttpStatus.BAD_REQUEST, "INVALID_BODY");
        verifyNoInteractions(generator, writer, repository);
    }

    @Test
    void retriesOnlyShortCodeUniqueConstraintViolation() {
        when(generator.generate()).thenReturn("collision", "aB12cD34");
        when(writer.insert("collision", ORIGINAL, null)).thenThrow(violation("23505", "uk_short_urls_short_code"));
        when(writer.insert("aB12cD34", ORIGINAL, null)).thenReturn(url(null, true));
        assertThat(service.create(new CreateShortUrlRequest(ORIGINAL, null)).shortCode()).isEqualTo("aB12cD34");
        verify(generator, times(2)).generate();
    }

    @Test
    void boundsRetriesWhenCodesKeepColliding() {
        when(generator.generate()).thenReturn("collision");
        when(writer.insert("collision", ORIGINAL, null)).thenThrow(violation("23505", "uk_short_urls_short_code"));
        assertError(() -> service.create(new CreateShortUrlRequest(ORIGINAL, null)),
                HttpStatus.SERVICE_UNAVAILABLE, "CODE_GENERATION_UNAVAILABLE");
        verify(writer, times(ShortUrlService.MAX_ATTEMPTS)).insert("collision", ORIGINAL, null);
        verify(generator, times(ShortUrlService.MAX_ATTEMPTS)).generate();
    }

    @Test
    void doesNotRetryUnrelatedUniqueConstraint() {
        assertNonRetriable(violation("23505", "another_unique_constraint"));
    }

    @Test
    void doesNotRetryOtherSqlState() {
        assertNonRetriable(violation("23502", "uk_short_urls_short_code"));
    }

    @Test
    void doesNotRetryUnclassifiedDatabaseFailure() {
        assertNonRetriable(new DataIntegrityViolationException("database failure"));
    }

    @Test
    void resolvesActiveUrlWithNoExpiration() {
        when(repository.findByShortCode("aB12cD34")).thenReturn(Optional.of(url(null, true)));
        assertThat(service.resolve("aB12cD34")).isEqualTo(ORIGINAL);
    }

    @Test
    void resolvesActiveUrlWithFutureExpiration() {
        when(repository.findByShortCode("aB12cD34")).thenReturn(Optional.of(url(NOW.plusNanos(1), true)));
        assertThat(service.resolve("aB12cD34")).isEqualTo(ORIGINAL);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void treatsExpirationAtOrBeforeNowAsGone(long seconds) {
        when(repository.findByShortCode("aB12cD34")).thenReturn(Optional.of(url(NOW.plusSeconds(seconds), true)));
        assertError(() -> service.resolve("aB12cD34"), HttpStatus.GONE, "URL_EXPIRED");
    }

    @Test
    void doesNotResolveInactiveUrl() {
        when(repository.findByShortCode("aB12cD34")).thenReturn(Optional.of(url(null, false)));
        assertError(() -> service.resolve("aB12cD34"), HttpStatus.GONE, "URL_INACTIVE");
    }

    @Test
    void missingCodeReturnsNotFound() {
        assertError(() -> service.resolve("missing"), HttpStatus.NOT_FOUND, "URL_NOT_FOUND");
    }

    @Test
    void managementCanReadInactiveExpiredUrl() {
        when(repository.findById(42L)).thenReturn(Optional.of(url(NOW.minusSeconds(1), false)));
        assertThat(service.get(42L).active()).isFalse();
        assertThat(service.get(42L).expiresAt()).isBefore(NOW);
    }

    @Test
    void missingIdReturnsNotFoundForGetAndDelete() {
        assertError(() -> service.get(42L), HttpStatus.NOT_FOUND, "URL_NOT_FOUND");
        assertError(() -> service.deactivate(42L), HttpStatus.NOT_FOUND, "URL_NOT_FOUND");
    }

    @Test
    void deactivationIsIdempotentAndDoesNotDeleteRow() {
        ShortUrl url = url(null, true);
        when(repository.findById(42L)).thenReturn(Optional.of(url));
        service.deactivate(42L);
        service.deactivate(42L);
        assertThat(url.isActive()).isFalse();
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    private ShortUrl url(Instant expiresAt, boolean active) {
        return new ShortUrl("aB12cD34", ORIGINAL, expiresAt, active);
    }

    private DataIntegrityViolationException violation(String sqlState, String constraint) {
        return new DataIntegrityViolationException("insert failed", new ConstraintViolationException(
                "constraint violation", new SQLException("detail", sqlState), constraint));
    }

    private void assertNonRetriable(DataIntegrityViolationException exception) {
        when(generator.generate()).thenReturn("aB12cD34");
        when(writer.insert(anyString(), eq(ORIGINAL), eq(null))).thenThrow(exception);
        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest(ORIGINAL, null))).isSameAs(exception);
        verify(generator).generate();
    }

    private void assertError(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(UrlException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }
}
