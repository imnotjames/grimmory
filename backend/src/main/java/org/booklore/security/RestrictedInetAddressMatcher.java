package org.booklore.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class RestrictedInetAddressMatcher {
    private record InetAddressRange(byte[] lower, byte[] upper) {
        public boolean matches(InetAddress address) {
            byte[] addressBytes = address.getAddress();
            return addressBytes.length == lower.length && Arrays.compareUnsigned(lower, addressBytes) <= 0 && Arrays.compareUnsigned(addressBytes, upper) <= 0;
        }

        private static int parseCidrMask(String cidrPrefix) {
            try {
                return Integer.parseInt(cidrPrefix);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix: " + cidrPrefix);
            }
        }

        private static InetAddress parseCidrAddress(String cidrAddress) {
            if (cidrAddress == null || cidrAddress.isBlank()) {
                throw new IllegalArgumentException("Empty CIDR Address");
            }

            // If the first character is a digit, getByName uses `IPAddressUtil`
            // and skips actual network calls.  Ensure we're properly skipping things
            // so we avoid network lookups.
            if (Character.digit(cidrAddress.charAt(0), 16) == -1 && cidrAddress.charAt(0) != ':') {
                throw new IllegalArgumentException("Invalid CIDR Address: " + cidrAddress);
            }

            try {
                return InetAddress.getByName(cidrAddress);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid CIDR Address: " + cidrAddress);
            }
        }

        private static InetAddressRange parse(String cidr) {
            String[] parts = cidr.split("/");

            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid CIDR: " + cidr);
            }

            InetAddress address = parseCidrAddress(parts[0]);
            int mask = parseCidrMask(parts[1]);

            byte[] lower = address.getAddress();
            byte[] upper = address.getAddress();

            for (var i = 0; i < lower.length; i++) {
                if (mask <= 0) {
                    lower[i] = (byte) 0x00;
                    upper[i] = (byte) 0xFF;
                } else if (mask < 8) {
                    lower[i] = (byte) (lower[i] & 0xFF << (8 - mask));
                    upper[i] = (byte) (upper[i] | ~(0xFF << (8 - mask)));
                }

                mask -= 8;
            }

            return new InetAddressRange(lower, upper);
        }
    }

    private static final List<InetAddressRange> RESTRICTED_RANGES = Set.of(
            // IPv6 Addressing of IPv4/IPv6 Translators - RFC 6052 - https://datatracker.ietf.org/doc/html/rfc6052
            "64:ff9b::/96",

            // Local-Use IPv4/IPv6 Translation Prefix - RFC 8215 - https://datatracker.ietf.org/doc/html/rfc8215
            "64:ff9b:1::/48",

            // Connection of IPv6 Domains via IPv4 Clouds - RFC 3056 - https://datatracker.ietf.org/doc/html/rfc3056
            "2002::/16",

            // Initial IPv6 Sub-TLA ID Assignments - RFC 2928 - https://datatracker.ietf.org/doc/html/rfc2928
            "2001::/23",

            // Unique Local IPv6 Unicast Addresses - RFC 4193 - https://datatracker.ietf.org/doc/html/rfc4193
            "fc00::/7",

            // An Anycast Prefix for 6to4 Relay Routers - RFC 3068 - https://datatracker.ietf.org/doc/html/rfc3068
            "192.88.99.0/24",

            // Loopback - RFC 6890 - https://datatracker.ietf.org/doc/html/rfc6890
            "127.0.0.0/8",

            // Link Local - RFC 6890 - https://datatracker.ietf.org/doc/html/rfc6890
            "169.254.0.0/16",

            // Private Use - RFC 6890 - https://datatracker.ietf.org/doc/html/rfc6890
            "172.16.0.0/12",

            // Shared Address Space - RFC 6890 - https://datatracker.ietf.org/doc/html/rfc6890
            "100.64.0.0/10",

            // IETF Protocol Assignments - RFC 6890 - https://datatracker.ietf.org/doc/html/rfc6890
            "192.0.0.0/24",

            // This host on this network - RFC 6890 - https://datatracker.ietf.org/doc/html/rfc6890
            "0.0.0.0/8",

            // Private Use - RFC 6890 - https://datatracker.ietf.org/doc/html/rfc6890
            "192.168.0.0/16",

            // TEST-NET-1 - RFC 5737 - https://datatracker.ietf.org/doc/html/rfc5737
            "192.0.2.0/24",

            // TEST-NET-2 - RFC 5737 - https://datatracker.ietf.org/doc/html/rfc5737
            "198.51.100.0/24",

            // TEST-NET-3 - RFC 5737 - https://datatracker.ietf.org/doc/html/rfc5737
            "203.0.113.0/24",

            // Network Interconnect Device Benchmark Testing - RFC 2544 - https://datatracker.ietf.org/doc/html/rfc2544
            "198.18.0.0/15",

            // Limited Broadcast - RFC 0919 - https://datatracker.ietf.org/doc/html/rfc0919
            "255.255.255.255/32"
    ).stream().map(InetAddressRange::parse).toList();

    private boolean isInRestrictedRange(InetAddress address) {
        return RESTRICTED_RANGES.stream().anyMatch(r -> r.matches(address));
    }

    public boolean isRestrictedAddress(InetAddress address) {
        return address.isLoopbackAddress() ||
                address.isLinkLocalAddress() ||
                address.isSiteLocalAddress() ||
                address.isAnyLocalAddress() ||
                isInRestrictedRange(address);
    }

    public boolean isRestrictedAddress(String host) throws IOException {
        // Validate resolved IPs to block SSRF against internal networks
        InetAddress[] inetAddresses = InetAddress.getAllByName(host);

        if (inetAddresses.length == 0) {
            throw new IOException("Unable to resolve host");
        }

        for (InetAddress inetAddress : inetAddresses) {
            if (isRestrictedAddress(inetAddress)) {
                log.debug("Address is restricted: {}", inetAddress);
                return true;
            }
        }

        return false;

    }
}
