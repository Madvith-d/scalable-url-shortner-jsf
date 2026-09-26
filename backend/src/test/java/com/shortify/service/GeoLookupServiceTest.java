package com.shortify.service;

import com.shortify.config.GeoLocationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GeoLookupServiceTest {

    private final LocalGeoDatabase local = mock(LocalGeoDatabase.class);
    private final PaidGeoProvider paid = mock(PaidGeoProvider.class);
    private final GeoLookupService service = service(true);

    @Test
    void completeLocalResultAvoidsDisclosingIpToRemoteProvider() {
        GeoLocation location = new GeoLocation("US", "Mountain View");
        when(local.lookup(any())).thenReturn(location);
        assertThat(service.lookup("8.8.8.8")).isEqualTo(location);
        verifyNoInteractions(paid);
    }

    @Test
    void enrichesCountryOnlyLocalResultWithMatchingRemoteCity() {
        when(local.lookup(any())).thenReturn(new GeoLocation("US", null));
        when(paid.lookup(eq("8.8.8.8"), anyLong())).thenReturn(new GeoLocation("US", "Mountain View"));
        assertThat(service.lookup("::ffff:8.8.8.8")).isEqualTo(new GeoLocation("US", "Mountain View"));
        verify(local).lookup(IpAddress.parse("8.8.8.8"));
    }

    @Test
    void neverCombinesConflictingCountriesAndCities() {
        when(local.lookup(any())).thenReturn(new GeoLocation("US", null));
        when(paid.lookup(eq("8.8.8.8"), anyLong())).thenReturn(new GeoLocation("CA", "Toronto"));
        assertThat(service.lookup("8.8.8.8")).isEqualTo(new GeoLocation("US", null));
    }

    @Test
    void preservesLocalCountryWhenRemoteProviderFails() {
        when(local.lookup(any())).thenReturn(new GeoLocation("US", null));
        when(paid.lookup(eq("8.8.8.8"), anyLong())).thenReturn(GeoLocation.UNKNOWN);
        assertThat(service.lookup("8.8.8.8")).isEqualTo(new GeoLocation("US", null));
    }

    @Test
    void usesRemotePairWhenLocalDatabaseHasNoMatch() {
        when(local.lookup(any())).thenReturn(GeoLocation.UNKNOWN);
        when(paid.lookup(eq("8.8.8.8"), anyLong())).thenReturn(new GeoLocation("CA", "Toronto"));
        assertThat(service.lookup("8.8.8.8")).isEqualTo(new GeoLocation("CA", "Toronto"));
    }

    @Test
    void unknownFromBothProvidersStaysUnknown() {
        when(local.lookup(any())).thenReturn(GeoLocation.UNKNOWN);
        when(paid.lookup(eq("8.8.8.8"), anyLong())).thenReturn(GeoLocation.UNKNOWN);
        assertThat(service.lookup("8.8.8.8")).isEqualTo(GeoLocation.UNKNOWN);
    }

    @Test
    void disabledMakesNoProviderCalls() {
        assertThat(service(false).lookup("8.8.8.8")).isEqualTo(GeoLocation.UNKNOWN);
        verifyNoInteractions(local, paid);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"localhost", "8.8.8.8:443", "127.0.0.1", "10.0.0.1", "192.0.2.1",
            "::ffff:127.0.0.1", "2001:db8::1", "fc00::1", "https://example.com"})
    void rejectsNonPublicInputBeforeEitherProvider(String ip) {
        assertThat(service.lookup(ip)).isEqualTo(GeoLocation.UNKNOWN);
        verifyNoInteractions(local, paid);
    }

    private GeoLookupService service(boolean enabled) {
        return new GeoLookupService(new GeoLocationProperties(enabled, "", "", ""), local, paid);
    }
}
