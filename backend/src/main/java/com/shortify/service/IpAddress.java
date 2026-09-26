package com.shortify.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

final class IpAddress {

    private static final List<Cidr> NON_PUBLIC_V4 = List.of(
            Cidr.parse("0.0.0.0/8"), Cidr.parse("10.0.0.0/8"), Cidr.parse("100.64.0.0/10"),
            Cidr.parse("127.0.0.0/8"), Cidr.parse("169.254.0.0/16"), Cidr.parse("172.16.0.0/12"),
            Cidr.parse("192.0.0.0/24"), Cidr.parse("192.0.2.0/24"), Cidr.parse("192.88.99.0/24"),
            Cidr.parse("192.168.0.0/16"), Cidr.parse("198.18.0.0/15"), Cidr.parse("198.51.100.0/24"),
            Cidr.parse("203.0.113.0/24"), Cidr.parse("224.0.0.0/3"));
    private static final List<Cidr> NON_PUBLIC_V6 = List.of(
            Cidr.parse("2001::/23"), Cidr.parse("2001:db8::/32"), Cidr.parse("2002::/16"),
            Cidr.parse("3fff::/20"));

    private IpAddress() {
    }

    static InetAddress parse(String literal) {
        if (literal == null || literal.isEmpty() || literal.length() > 45) {
            return null;
        }
        byte[] bytes = literal.indexOf(':') < 0 ? ipv4(literal) : ipv6(literal);
        if (bytes == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return NON_PUBLIC_V4.stream().noneMatch(cidr -> cidr.contains(address));
        }
        // Only allocated global unicast space; exclude special-purpose and transition ranges.
        return (bytes[0] & 0xe0) == 0x20
                && NON_PUBLIC_V6.stream().noneMatch(cidr -> cidr.contains(address));
    }

    private static byte[] ipv4(String literal) {
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char digit = part.charAt(j);
                if (digit < '0' || digit > '9') {
                    return null;
                }
                value = value * 10 + digit - '0';
            }
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] ipv6(String literal) {
        if (literal.indexOf('.') >= 0) {
            int colon = literal.lastIndexOf(':');
            byte[] tail = ipv4(literal.substring(colon + 1));
            if (tail == null) {
                return null;
            }
            literal = literal.substring(0, colon + 1)
                    + Integer.toHexString((tail[0] & 255) * 256 + (tail[1] & 255)) + ":"
                    + Integer.toHexString((tail[2] & 255) * 256 + (tail[3] & 255));
        }
        int compression = literal.indexOf("::");
        if (compression >= 0 && literal.indexOf("::", compression + 2) >= 0) {
            return null;
        }
        String left = compression < 0 ? literal : literal.substring(0, compression);
        String right = compression < 0 ? "" : literal.substring(compression + 2);
        String[] before = left.isEmpty() ? new String[0] : left.split(":", -1);
        String[] after = right.isEmpty() ? new String[0] : right.split(":", -1);
        int count = before.length + after.length;
        if ((compression < 0 && count != 8) || (compression >= 0 && count >= 8)) {
            return null;
        }
        byte[] bytes = new byte[16];
        return writeWords(before, bytes, 0) && writeWords(after, bytes, 8 - after.length) ? bytes : null;
    }

    private static boolean writeWords(String[] words, byte[] bytes, int offset) {
        for (String word : words) {
            if (word.isEmpty() || word.length() > 4) {
                return false;
            }
            int value = 0;
            for (int i = 0; i < word.length(); i++) {
                char digit = word.charAt(i);
                int hex = digit >= '0' && digit <= '9' ? digit - '0'
                        : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10
                        : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                if (hex < 0) {
                    return false;
                }
                value = value * 16 + hex;
            }
            bytes[offset * 2] = (byte) (value >>> 8);
            bytes[offset * 2 + 1] = (byte) value;
            offset++;
        }
        return true;
    }

    record Cidr(byte[] network, int prefix) {

        static Cidr parse(String value) {
            String[] parts = value.split("/", -1);
            InetAddress address = parts.length == 2 ? IpAddress.parse(parts[0]) : null;
            if (address == null || !parts[1].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("Trusted proxies must be literal IP CIDRs.");
            }
            int prefix = Integer.parseInt(parts[1]);
            // Mapped IPv4 literals are normalized before applying IPv4 CIDR widths.
            if (parts[0].contains(":") && address.getAddress().length == 4) {
                prefix -= 96;
            }
            if (prefix < 0 || prefix > address.getAddress().length * 8) {
                throw new IllegalArgumentException("Trusted proxy CIDR prefix is out of range.");
            }
            return new Cidr(address.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int whole = prefix / 8;
            int remainder = prefix % 8;
            return Arrays.equals(network, 0, whole, candidate, 0, whole)
                    && (remainder == 0 || ((network[whole] ^ candidate[whole]) & (255 << (8 - remainder))) == 0);
        }
    }
}
