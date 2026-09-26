package com.shortify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "shortify.geolocation")
public record GeoLocationProperties(@DefaultValue("true") boolean enabled,
                                    @DefaultValue("") String databasePath,
                                    @DefaultValue("") String apiKey,
                                    @DefaultValue("") String trustedProxies) {

    @Override
    public String toString() {
        return "GeoLocationProperties[enabled=" + enabled + "]";
    }
}
