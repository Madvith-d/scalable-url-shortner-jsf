package com.shortify.service;

import com.shortify.config.GeoLocationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private final ClientIpResolver direct = resolver("");
    private final ClientIpResolver proxied = resolver("10.0.0.0/8, 2001:4860:1234::/48");

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "9.9.9.9", "192.0.1.1", "223.255.255.254",
            "2001:4860:4860::8888", "2606:4700:4700::1111", "2a00:1450:4001:800::200e"})
    void acceptsAndNormalizesPublicLiterals(String ip) {
        assertThat(direct.resolve(request(ip, null))).isEqualTo(IpAddress.parse(ip).getHostAddress());
    }

    @ParameterizedTest
    @ValueSource(strings = {"::ffff:8.8.8.8", "0:0:0:0:0:FFFF:0808:0808"})
    void normalizesMappedIpv4(String ip) {
        assertThat(direct.resolve(request(ip, null))).isEqualTo("8.8.8.8");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"localhost", "example.com", "8.8.8.8.example.com", "2130706433", "127.1",
            "0177.0.0.1", "0x7f.0.0.1", "008.8.8.8", "8.8.8.256", "8.8.8.-1", "+8.8.8.8",
            "8.8.8.8:443", "[2001:4860::1]", "[2001:4860::1]:443", "2001:4860::1%eth0",
            "2001:::1", "2001::1::2", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "::ffff:127.01.0.1",
            " 8.8.8.8", "8.8.8.8 ", "8.8.8.8\n", "8.8.8.8,1.1.1.1", "８.8.8.8", "2001:gggg::1"})
    void rejectsMalformedLiteralsWithoutResolvingHostnames(String ip) {
        assertThat(direct.resolve(request(ip, "1.1.1.1"))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.255.255",
            "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.255", "192.0.0.9", "192.0.2.1",
            "192.88.99.1", "192.168.0.1", "198.18.0.1", "198.19.255.255", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255",
            "::", "::1", "::8.8.8.8", "::ffff:192.168.1.1", "64:ff9b::808:808", "64:ff9b:1::1",
            "100::1", "2001::1", "2001:2::1", "2001:20::1", "2001:db8::1", "2002:808:808::1",
            "3fff::1", "3fff:fff::1", "5f00::1", "fc00::1", "fdff::1", "fe80::1", "fec0::1", "ff02::1"})
    void filtersNonPublicAndSpecialPurposeAddresses(String ip) {
        assertThat(direct.resolve(request(ip, null))).isNull();
    }

    @Test
    void disabledGeolocationDoesNotExtractAnAddress() {
        var disabled = new ClientIpResolver(new GeoLocationProperties(false, "", "", "10.0.0.0/8"));
        assertThat(disabled.resolve(request("8.8.8.8", null))).isNull();
        assertThat(disabled.resolve(request("10.0.0.1", "8.8.8.8"))).isNull();
    }

    @Test
    void ignoresForwardingHeadersFromUntrustedPeers() {
        MockHttpServletRequest request = request("8.8.8.8", "malformed, 1.1.1.1");
        request.addHeader("Forwarded", "for=9.9.9.9");
        request.addHeader("X-Real-IP", "9.9.9.9");
        assertThat(direct.resolve(request)).isEqualTo("8.8.8.8");
        assertThat(direct.resolve(request("10.0.0.1", "8.8.8.8"))).isNull();
    }

    @Test
    void walksFromRightAndStopsAtFirstUntrustedHop() {
        assertThat(proxied.resolve(request("10.0.0.1", "9.9.9.9, 8.8.8.8, 10.2.3.4")))
                .isEqualTo("8.8.8.8");
        assertThat(proxied.resolve(request("::ffff:10.0.0.1", "::ffff:8.8.8.8, 10.2.3.4")))
                .isEqualTo("8.8.8.8");
        assertThat(proxied.resolve(request("2001:4860:1234::1", "2606:4700::1111, 10.2.3.4")))
                .isEqualTo("2606:4700:0:0:0:0:0:1111");
    }

    @Test
    void doesNotSkipUntrustedPrivateHopsToReachSpoofedPublicAddress() {
        assertThat(proxied.resolve(request("10.0.0.1", "8.8.8.8, 192.168.1.1"))).isNull();
        assertThat(proxied.resolve(request("10.0.0.1", "8.8.8.8, 2001:db8::1"))).isNull();
    }

    @Test
    void processesRepeatedHeaderLinesInWireOrder() {
        MockHttpServletRequest request = request("10.0.0.1", "9.9.9.9, 8.8.8.8");
        request.addHeader("X-Forwarded-For", "10.1.1.1, 10.2.2.2");
        assertThat(proxied.resolve(request)).isEqualTo("8.8.8.8");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown", "8.8.8.8,", ",8.8.8.8", "8.8.8.8,,10.1.1.1",
            "garbage, 8.8.8.8", "8.8.8.8, [2001:4860::1]", "8.8.8.8\r\n", "8.8.8.8\t",
            "8.8.8.8:443", "for=8.8.8.8", "10.1.1.1, 10.2.2.2"})
    void failsClosedForMalformedOrUnestablishedProxyOrigins(String header) {
        assertThat(proxied.resolve(request("10.0.0.1", header))).isNull();
    }

    @Test
    void boundsHeaderLengthAndTotalHopsAcrossHeaderLines() {
        assertThat(proxied.resolve(request("10.0.0.1", " ".repeat(4090) + "8.8.8.8"))).isNull();
        assertThat(proxied.resolve(request("10.0.0.1", "8.8.8.8," + "10.1.1.1,".repeat(31) + "10.1.1.1")))
                .isNull();
        assertThat(proxied.resolve(request("10.0.0.1", "8.8.8.8," + "10.1.1.1,".repeat(30) + "10.1.1.1")))
                .isEqualTo("8.8.8.8");
        MockHttpServletRequest repeated = request("10.0.0.1", "8.8.8.8");
        for (int i = 0; i < 32; i++) {
            repeated.addHeader("X-Forwarded-For", "10.1.1.1");
        }
        assertThat(proxied.resolve(repeated)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost/8", "10.0.0.1", "10.0.0.0/-1", "10.0.0.0/33", "::1/129",
            "10.0.0.0/8,", "10.0.0.0/8,,127.0.0.0/8", "::ffff:10.0.0.0/80"})
    void rejectsInvalidTrustConfigurationWithoutEchoingIt(String cidr) {
        assertThatThrownBy(() -> resolver(cidr)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(cidr);
    }

    @Test
    void supportsCidrBoundaryBitsAndMappedProxyConfiguration() {
        ClientIpResolver narrow = resolver("10.0.0.128/25, ::ffff:192.168.0.0/112");
        assertThat(narrow.resolve(request("10.0.0.127", "8.8.8.8"))).isNull();
        assertThat(narrow.resolve(request("10.0.0.128", "8.8.8.8"))).isEqualTo("8.8.8.8");
        assertThat(narrow.resolve(request("192.168.1.1", "8.8.8.8"))).isEqualTo("8.8.8.8");
    }

    private static ClientIpResolver resolver(String proxies) {
        return new ClientIpResolver(new GeoLocationProperties(true, "", "", proxies));
    }

    private static MockHttpServletRequest request(String peer, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (header != null) {
            request.addHeader("X-Forwarded-For", header);
        }
        return request;
    }
}
