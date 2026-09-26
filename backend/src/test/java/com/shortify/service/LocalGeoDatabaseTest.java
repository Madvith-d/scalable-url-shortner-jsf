package com.shortify.service;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.shortify.config.GeoLocationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class LocalGeoDatabaseTest {

    @TempDir
    Path temporary;

    @Test
    void absentMissingAndInvalidDatabasesAreOptional(CapturedOutput output) throws IOException {
        Path invalid = temporary.resolve("private-database-path.mmdb");
        Files.writeString(invalid, "not a MaxMind database");
        for (String path : new String[]{"", temporary.resolve("missing.mmdb").toString(), invalid.toString()}) {
            LocalGeoDatabase database = new LocalGeoDatabase(properties(true, path));
            assertThat(database.lookup(IpAddress.parse("8.8.8.8"))).isEqualTo(GeoLocation.UNKNOWN);
            database.close();
        }
        assertThat(output.getAll()).doesNotContain("8.8.8.8", "private-database-path", "missing.mmdb", "server-secret");
    }

    @Test
    void disabledDoesNotAttemptToOpenDatabase(CapturedOutput output) {
        LocalGeoDatabase database = new LocalGeoDatabase(properties(false, temporary.resolve("missing.mmdb").toString()));
        assertThat(database.lookup(IpAddress.parse("8.8.8.8"))).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(output.getAll()).doesNotContain("database unavailable");
        database.close();
    }

    @Test
    void reusesOneReaderAcrossConcurrentLookupsAndClosesItOnce() throws Exception {
        DatabaseReader reader = mock(DatabaseReader.class);
        CityResponse city = mock(CityResponse.class, RETURNS_DEEP_STUBS);
        when(city.getCountry().getIsoCode()).thenReturn("us");
        when(city.getCity().getName()).thenReturn("Mountain View");
        when(reader.tryCity(any())).thenReturn(Optional.of(city));
        LocalGeoDatabase database = new LocalGeoDatabase(reader);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var futures = IntStream.range(0, 32)
                    .mapToObj(index -> workers.submit(() -> database.lookup(IpAddress.parse("8.8.8.8"))))
                    .toList();
            for (var future : futures) {
                assertThat(future.get()).isEqualTo(new GeoLocation("US", "Mountain View"));
            }
        }
        verify(reader, times(32)).tryCity(any());
        database.close();
        database.close();
        verify(reader).close();
        assertThat(database.lookup(IpAddress.parse("8.8.8.8"))).isEqualTo(GeoLocation.UNKNOWN);
        verify(reader, times(32)).tryCity(any());
    }

    @Test
    void handlesNoMatchCountryOnlyAndReaderFailureWithoutLoggingIp(CapturedOutput output) throws Exception {
        InetAddress address = IpAddress.parse("8.8.8.8");
        DatabaseReader reader = mock(DatabaseReader.class);
        CityResponse city = mock(CityResponse.class, RETURNS_DEEP_STUBS);
        when(city.getCountry().getIsoCode()).thenReturn("US");
        when(city.getCity().getName()).thenReturn(null);
        when(reader.tryCity(address)).thenReturn(Optional.empty(), Optional.of(city))
                .thenThrow(new IOException("private address 8.8.8.8 and server-secret"));
        LocalGeoDatabase database = new LocalGeoDatabase(reader);
        assertThat(database.lookup(address)).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(database.lookup(address)).isEqualTo(new GeoLocation("US", null));
        assertThat(database.lookup(address)).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(output.getAll()).doesNotContain("8.8.8.8", "server-secret");
        database.close();
    }

    private static GeoLocationProperties properties(boolean enabled, String path) {
        return new GeoLocationProperties(enabled, path, "server-secret", "");
    }
}
