package com.shortify.service;

import java.net.InetAddress;

import com.shortify.config.GeoLocationProperties;
import org.springframework.stereotype.Service;

@Service
public class GeoLookupService {

    private final boolean enabled;
    private final LocalGeoDatabase local;
    private final PaidGeoProvider paid;

    public GeoLookupService(GeoLocationProperties properties, LocalGeoDatabase local, PaidGeoProvider paid) {
        enabled = properties.enabled();
        this.local = local;
        this.paid = paid;
    }

    public GeoLocation lookup(String ip) {
        long started = System.nanoTime();
        InetAddress address = IpAddress.parse(ip);
        if (!enabled || address == null || !IpAddress.isPublic(address)) {
            return GeoLocation.UNKNOWN;
        }
        GeoLocation location = local.lookup(address);
        if (location.city() != null) {
            return location;
        }
        GeoLocation fallback = paid.lookup(address.getHostAddress(), started);
        if (fallback.countryCode() == null || (location.countryCode() != null
                && !location.countryCode().equals(fallback.countryCode()))) {
            return location;
        }
        return fallback;
    }
}
