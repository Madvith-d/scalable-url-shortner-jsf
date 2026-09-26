package com.shortify.service;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import com.shortify.config.GeoLocationProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class ClientIpResolver {

    static final int MAX_HEADER_LENGTH = 4096;
    static final int MAX_HOPS = 32;
    private final List<IpAddress.Cidr> trustedProxies;
    private final boolean enabled;

    public ClientIpResolver(GeoLocationProperties properties) {
        enabled = properties.enabled();
        String configured = properties.trustedProxies();
        if (configured == null || configured.isBlank()) {
            trustedProxies = List.of();
        } else {
            if (configured.length() > 8192) {
                throw new IllegalArgumentException("Too many trusted proxy CIDRs.");
            }
            String[] cidrs = configured.split(",", -1);
            if (cidrs.length > 128) {
                throw new IllegalArgumentException("Too many trusted proxy CIDRs.");
            }
            trustedProxies = java.util.Arrays.stream(cidrs).map(String::strip).map(IpAddress.Cidr::parse).toList();
        }
    }

    public String resolve(HttpServletRequest request) {
        if (!enabled) {
            return null;
        }
        InetAddress peer = IpAddress.parse(request.getRemoteAddr());
        if (peer == null) {
            return null;
        }
        if (!trusted(peer)) {
            return publicLiteral(peer);
        }
        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        List<InetAddress> hops = new ArrayList<>();
        int length = 0;
        while (headers != null && headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (header == null || header.length() > MAX_HEADER_LENGTH - length) {
                return null;
            }
            length += header.length() + 1;
            if (header.isEmpty() || header.codePoints().anyMatch(Character::isISOControl)) {
                return null;
            }
            for (String token : header.split(",", -1)) {
                if (hops.size() == MAX_HOPS) {
                    return null;
                }
                InetAddress address = IpAddress.parse(token.strip());
                if (address == null) {
                    return null;
                }
                hops.add(address);
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress address = hops.get(i);
            if (!trusted(address)) {
                return publicLiteral(address);
            }
        }
        // No untrusted origin was established; never use a proxy as the visitor.
        return null;
    }

    private boolean trusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static String publicLiteral(InetAddress address) {
        return IpAddress.isPublic(address) ? address.getHostAddress() : null;
    }
}
