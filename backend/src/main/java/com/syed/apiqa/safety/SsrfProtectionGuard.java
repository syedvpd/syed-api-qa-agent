package com.syed.apiqa.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.*;
import java.util.Arrays;
import java.util.List;

/**
 * SSRF & Private IP Guard with Anti-DNS Rebinding / Pinning Protection.
 * Prevents requests to cloud instance metadata services (169.254.169.254), Carrier-Grade NAT,
 * and internal subnets.
 *
 * Provides safe controlled local development support when explicitly enabled.
 * Provides IP pinning via ValidatedTarget to eliminate Time-of-Check to Time-of-Use (TOCTOU)
 * DNS rebinding attacks where an attacker changes DNS records between validation and connection.
 */
@Component
public class SsrfProtectionGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfProtectionGuard.class);

    @Value("${syed.safety.ssrf-protection-enabled:true}")
    private boolean ssrfProtectionEnabled;

    @Value("${syed.safety.allow-local-targets:false}")
    private boolean allowLocalTargets;

    private static final List<String> ALWAYS_BLOCKED_HOSTS = Arrays.asList(
            "metadata.google.internal",
            "169.254.169.254",
            "100.100.100.200"
    );

    private static final List<String> LOCAL_HOSTS = Arrays.asList(
            "localhost",
            "127.0.0.1",
            "::1"
    );

    public record ValidatedTarget(
            URI originalUri,
            InetAddress pinnedAddress,
            String originalHost,
            int port,
            String pinnedUrl,
            String originalHostHeader,
            boolean isPinned
    ) {}

    public boolean isSsrfProtectionEnabled() {
        return ssrfProtectionEnabled;
    }

    public void setSsrfProtectionEnabled(boolean ssrfProtectionEnabled) {
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
    }

    public boolean isAllowLocalTargets() {
        return allowLocalTargets;
    }

    public void setAllowLocalTargets(boolean allowLocalTargets) {
        this.allowLocalTargets = allowLocalTargets;
    }

    /**
     * Legacy validation method preserving backwards compatibility.
     */
    public void validateTargetUrl(String urlString) {
        resolveAndValidate(urlString, this.allowLocalTargets);
    }

    public void validateTargetUrl(String urlString, boolean allowLocal) {
        resolveAndValidate(urlString, allowLocal);
    }

    /**
     * Resolves the target hostname once, strictly validates all resolved IP addresses,
     * and produces a pinned target that connects directly to the validated IP without secondary DNS resolution.
     */
    public ValidatedTarget resolveAndValidate(String urlString) {
        return resolveAndValidate(urlString, this.allowLocalTargets);
    }

    public ValidatedTarget resolveAndValidate(String urlString, boolean allowLocal) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("Target URL cannot be empty");
        }

        URI uri;
        try {
            uri = URI.create(urlString);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL syntax: " + urlString, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SecurityException("Blocked insecure protocol: " + scheme + ". Only HTTP and HTTPS are permitted.");
        }

        // Block userinfo in URL (e.g. http://user@evil.com, http://admin:pass@169.254.169.254)
        if (uri.getUserInfo() != null || urlString.contains("@")) {
            throw new SecurityException("SSRF Guard: URLs with userinfo (@) are blocked.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Target URL does not contain a valid host: " + urlString);
        }

        int port = uri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }

        String hostHeader = (uri.getPort() == -1) ? host : (host + ":" + uri.getPort());

        // In test mode with SSRF disabled (e.g. WireMock on localhost), allow loopback
        if (!ssrfProtectionEnabled) {
            return new ValidatedTarget(uri, null, host, port, urlString, hostHeader, false);
        }

        if (ALWAYS_BLOCKED_HOSTS.contains(host.toLowerCase())) {
            throw new SecurityException("SSRF Guard: Target host is strictly blocked: " + host);
        }

        if (!allowLocal && LOCAL_HOSTS.contains(host.toLowerCase())) {
            throw new SecurityException("SSRF Guard: Target host is strictly blocked in production mode: " + host + ". Enable local dev mode to test localhost.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve host: " + host, e);
        }

        if (addresses.length == 0) {
            throw new SecurityException("SSRF Guard: Host resolved to no IP addresses: " + host);
        }

        // Validate EVERY resolved address. If any address is blocked, reject to prevent multi-A record bypasses
        for (InetAddress address : addresses) {
            checkAddress(address, allowLocal);
        }

        // Pin to the first validated address to prevent DNS rebinding
        InetAddress pinnedAddress = addresses[0];
        String pinnedHost = pinnedAddress.getHostAddress();
        if (pinnedAddress instanceof Inet6Address) {
            pinnedHost = "[" + pinnedHost + "]";
        }

        // Construct pinned URL with IP address replacing the host
        String pinnedUrl;
        try {
            URI pinnedUri = new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    pinnedHost,
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
            pinnedUrl = pinnedUri.toASCIIString();
        } catch (Exception e) {
            // Fallback manual replacement if URI constructor fails on IPv6
            pinnedUrl = urlString.replace("://" + host, "://" + pinnedHost);
        }

        return new ValidatedTarget(uri, pinnedAddress, host, port, pinnedUrl, hostHeader, true);
    }

    private void checkAddress(InetAddress address, boolean allowLocal) {
        if (isCloudMetadataAddress(address)) {
            throw new SecurityException("SSRF Guard: Cloud metadata IP blocked: " + address.getHostAddress());
        }
        if (isCarrierGradeNat(address)) {
            throw new SecurityException("SSRF Guard: Carrier-Grade NAT IP blocked: " + address.getHostAddress());
        }
        if (isIpv4MappedIpv6(address)) {
            throw new SecurityException("SSRF Guard: IPv4-mapped IPv6 address blocked: " + address.getHostAddress());
        }
        if (address.isLinkLocalAddress()) {
            throw new SecurityException("SSRF Guard: Link-local target blocked: " + address.getHostAddress());
        }
        if (address.isAnyLocalAddress()) {
            throw new SecurityException("SSRF Guard: Wildcard/any-local address blocked: " + address.getHostAddress());
        }

        if (allowLocal) {
            // In explicit local development mode, allow loopback and RFC 1918 private LAN addresses
            return;
        }

        if (address.isLoopbackAddress()) {
            throw new SecurityException("SSRF Guard: Loopback target blocked: " + address.getHostAddress());
        }
        if (address.isSiteLocalAddress()) {
            throw new SecurityException("SSRF Guard: Private network target blocked: " + address.getHostAddress());
        }
        if (isIpv6SiteLocalOrUniqueLocal(address)) {
            throw new SecurityException("SSRF Guard: IPv6 private/unique-local address blocked: " + address.getHostAddress());
        }
    }

    private boolean isCloudMetadataAddress(InetAddress address) {
        String ip = address.getHostAddress();
        return "169.254.169.254".equals(ip)
                || "169.254.169.250".equals(ip)
                || "169.254.169.251".equals(ip)
                || "100.100.100.200".equals(ip);
    }

    private boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // 100.64.0.0/10 (100.64.0.0 to 100.127.255.255)
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 100 && (second >= 64 && second <= 127);
        }
        return false;
    }

    private boolean isIpv4MappedIpv6(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            // IPv4-mapped IPv6 format: ::ffff:192.0.2.128 (first 10 bytes 0, next 2 bytes 0xFF)
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) return false;
            }
            if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
                return false;
            }
            // Extract the embedded IPv4 address and check it
            try {
                byte[] ipv4Bytes = new byte[4];
                System.arraycopy(bytes, 12, ipv4Bytes, 0, 4);
                InetAddress embeddedIpv4 = InetAddress.getByAddress(ipv4Bytes);
                return embeddedIpv4.isLoopbackAddress() || embeddedIpv4.isSiteLocalAddress();
            } catch (UnknownHostException e) {
                return true;
            }
        }
        return false;
    }

    private boolean isIpv6SiteLocalOrUniqueLocal(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            // fc00::/7 unique local addresses (ULA)
            int firstByte = bytes[0] & 0xFF;
            return (firstByte & 0xFE) == 0xFC;
        }
        return false;
    }
}
