package com.shortify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "shortify.geolocation")
public record GeoLocationProperties(@DefaultValue("true") boolean enabled,
                                    @DefaultValue("") String databasePath,
                                    @DefaultValue("") String apiKey,
                                    @DefaultValue("") String trustedProxies,
                                    @DefaultValue("true") boolean freeFallbackEnabled) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public GeoLocationProperties { }

    public GeoLocationProperties(boolean enabled, String databasePath, String apiKey, String trustedProxies) {
        this(enabled, databasePath, apiKey, trustedProxies, false);
    }

    @Override
    public String toString() {
        return "GeoLocationProperties[enabled=" + enabled + "]";
    }
}
