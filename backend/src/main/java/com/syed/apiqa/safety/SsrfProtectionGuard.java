package com.syed.apiqa.safety;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * SSRF & Private IP Guard.
 * Prevents requests to localhost, loopback (127.0.0.1/8), private RFC 1918 subnets,
 * IPv6 loopbacks, and cloud instance metadata services (169.254.169.254).
 */
@Component
public class SsrfProtectionGuard {

    @Value("${syed.safety.ssrf-protection-enabled:true}")
    private boolean ssrfProtectionEnabled;

    private static final List<String> BLOCKED_HOSTS = Arrays.asList(
            "localhost",
            "127.0.0.1",
            "::1",
            "metadata.google.internal",
            "169.254.169.254"
    );

    public void validateTargetUrl(String urlString) {
        if (!ssrfProtectionEnabled) {
            return;
        }

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

        if (BLOCKED_HOSTS.contains(host.toLowerCase())) {
            throw new SecurityException("SSRF Guard: Target host is strictly blocked: " + host);
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress()) {
                    throw new SecurityException("SSRF Guard: Loopback target blocked: " + address.getHostAddress());
                }
                if (address.isSiteLocalAddress()) {
                    throw new SecurityException("SSRF Guard: Private network target blocked: " + address.getHostAddress());
                }
                if (address.isLinkLocalAddress()) {
                    throw new SecurityException("SSRF Guard: Link-local target blocked: " + address.getHostAddress());
                }
                if (address.isAnyLocalAddress()) {
                    throw new SecurityException("SSRF Guard: Wildcard/any-local address blocked: " + address.getHostAddress());
                }
                if (isCloudMetadataAddress(address)) {
                    throw new SecurityException("SSRF Guard: Cloud metadata IP blocked: " + address.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve host: " + host, e);
        }
    }

    private boolean isCloudMetadataAddress(InetAddress address) {
        String ip = address.getHostAddress();
        return "169.254.169.254".equals(ip);
    }
}
