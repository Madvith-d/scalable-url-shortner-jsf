package com.shortify.config;

import com.shortify.service.ClientIpResolver;
import com.shortify.service.GeoLocation;
import com.shortify.service.GeoLookupService;
import com.shortify.service.LocalGeoDatabase;
import com.shortify.service.PaidGeoProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class GeoLocationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GeoLocationConfiguration.class, ClientIpResolver.class,
                    GeoLookupService.class, LocalGeoDatabase.class, PaidGeoProvider.class);

    @Test
    void defaultConfigurationStartsWithoutDatabaseKeyOrTrustedProxies() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ClientIpResolver.class).hasSingleBean(GeoLookupService.class);
            GeoLocationProperties properties = context.getBean(GeoLocationProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.databasePath()).isEmpty();
            assertThat(properties.apiKey()).isEmpty();
            assertThat(properties.trustedProxies()).isEmpty();
            assertThat(context.getBean(GeoLookupService.class).lookup("8.8.8.8")).isEqualTo(GeoLocation.UNKNOWN);
        });
    }

    @Test
    void bindsSettingsAndDoesNotExposeSecretInPropertiesToString() {
        runner.withPropertyValues("shortify.geolocation.enabled=false", "shortify.geolocation.api-key=server-secret",
                "shortify.geolocation.database-path=/optional/database.mmdb",
                "shortify.geolocation.trusted-proxies=10.0.0.0/8,192.168.0.0/16").run(context -> {
                    assertThat(context).hasNotFailed();
                    GeoLocationProperties properties = context.getBean(GeoLocationProperties.class);
                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.apiKey()).isEqualTo("server-secret");
                    assertThat(properties.toString()).doesNotContain("server-secret", "/optional/database", "10.0.0.0");
                    assertThat(context.getBean(GeoLookupService.class).lookup("8.8.8.8")).isEqualTo(GeoLocation.UNKNOWN);
                });
    }

    @Test
    void shippedConfigurationDisablesHttpDiagnosticsAndKeepsForwardingUnchanged() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("logging.level.org.apache.hc.client5.http")).isEqualTo("OFF");
        assertThat(properties.getProperty("logging.level.org.apache.hc.core5.http")).isEqualTo("OFF");
        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(properties.getProperty("shortify.geolocation.enabled")).isEqualTo("${GEO_ENABLED:true}");
        assertThat(properties.getProperty("shortify.geolocation.database-path")).isEqualTo("${GEO_DATABASE_PATH:}");
        assertThat(properties.getProperty("shortify.geolocation.api-key")).isEqualTo("${GEO_API_KEY:}");
        assertThat(properties.getProperty("shortify.geolocation.trusted-proxies")).isEqualTo("${GEO_TRUSTED_PROXIES:}");
    }
}
