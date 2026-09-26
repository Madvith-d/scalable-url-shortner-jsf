package com.shortify.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GeoLocationProperties.class)
public class GeoLocationConfiguration {
}
