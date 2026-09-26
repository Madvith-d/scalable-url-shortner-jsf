package com.shortify.service;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.shortify.config.GeoLocationProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LocalGeoDatabase {

    private static final Logger log = LoggerFactory.getLogger(LocalGeoDatabase.class);
    private final DatabaseReader reader;
    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired
    public LocalGeoDatabase(GeoLocationProperties properties) {
        this(open(properties));
    }

    LocalGeoDatabase(DatabaseReader reader) {
        this.reader = reader;
    }

    GeoLocation lookup(InetAddress address) {
        if (reader == null || closed.get()) {
            return GeoLocation.UNKNOWN;
        }
        try {
            return reader.tryCity(address)
                    .map(city -> new GeoLocation(city.getCountry().getIsoCode(), city.getCity().getName()))
                    .orElse(GeoLocation.UNKNOWN);
        } catch (IOException | GeoIp2Exception | RuntimeException exception) {
            return GeoLocation.UNKNOWN;
        }
    }

    private static DatabaseReader open(GeoLocationProperties properties) {
        if (!properties.enabled() || properties.databasePath() == null || properties.databasePath().isBlank()) {
            return null;
        }
        try {
            return new DatabaseReader.Builder(Path.of(properties.databasePath()).toFile())
                    .withCache(new CHMCache(4096)).build();
        } catch (IOException | RuntimeException exception) {
            log.warn("Local geolocation database unavailable; using configured fallback only.");
            return null;
        }
    }

    @PreDestroy
    public void close() {
        if (reader != null && closed.compareAndSet(false, true)) {
            try {
                reader.close();
            } catch (IOException exception) {
                log.warn("Local geolocation database could not be closed cleanly.");
            }
        }
    }
}
