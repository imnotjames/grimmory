package org.booklore.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.security.RestrictedInetAddressMatcher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

@Slf4j
@RequiredArgsConstructor
@Service
public class UntrustedHttpRequestService {
    private static final int MAX_REDIRECTS = 5;

    private static final String USER_AGENT = "Grimmory/1.0 (Book and Comic Metadata Fetcher; +https://github.com/grimmory-tools/grimmory)";

    private final RestTemplate noRedirectRestTemplate;

    private final RestrictedInetAddressMatcher restrictedInetAddressMatcher;

    public byte[] request(String url) throws IOException {
        return request(HttpMethod.GET, url, HttpHeaders.EMPTY, byte[].class);
    }

    public byte[] request(HttpMethod method, String url) throws IOException {
        return request(method, url, HttpHeaders.EMPTY, byte[].class);
    }

    public byte[] request(HttpMethod method, String url, HttpHeaders extraHeaders) throws IOException {
        return request(method, url, extraHeaders, byte[].class);
    }

    public <T> T request(HttpMethod method, String url, HttpHeaders extraHeaders, Class<T> responseClass) throws IOException {
        String currentUrl = url;
        int redirectCount = 0;

        while (redirectCount <= MAX_REDIRECTS) {
            URI uri = URI.create(currentUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Only HTTP and HTTPS protocols are allowed");
            }

            String host = uri.getHost();
            if (host == null) {
                throw new IOException("Invalid URL: no host found in " + currentUrl);
            }

            if (restrictedInetAddressMatcher.isRestrictedAddress(host)) {
                throw new SecurityException("URL points to a restricted network address: " + host);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
            headers.addAll(extraHeaders);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.debug("Downloading data from: {}", currentUrl);

            ResponseEntity<T> response = noRedirectRestTemplate.exchange(
                    currentUrl,
                    method,
                    entity,
                    responseClass
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            if (response.getStatusCode().is3xxRedirection()) {
                String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                if (location == null) {
                    throw new IOException("Redirection response without Location header");
                }
                URI redirectUri = uri.resolve(location);

                // When a CDN redirects to a raw IP (e.g. CloudFront -> 3.168.64.124),
                // the Host header would become the bare IP, which the CDN rejects with
                // 400. Rewrite the URL to keep the previous hostname so the JDK
                // HttpClient sets the correct Host header automatically.
                if (isRawIpAddress(redirectUri.getHost())) {
                    try {
                        redirectUri = new URI(
                                redirectUri.getScheme(),
                                redirectUri.getUserInfo(),
                                host,
                                redirectUri.getPort(),
                                redirectUri.getPath(),
                                redirectUri.getQuery(),
                                redirectUri.getFragment()
                        );
                    } catch (URISyntaxException e) {
                        throw new IOException("Invalid redirect URI: " + e.getMessage(), e);
                    }
                }

                currentUrl = redirectUri.toString();
                redirectCount++;
                continue;
            }

            throw new IOException("Failed to download data. HTTP Status: " + response.getStatusCode());
        }

        throw new IOException("Too many redirects (max " + MAX_REDIRECTS + ")");
    }

    private boolean isRawIpAddress(String host) {
        if (host == null) {
            return false;
        }
        // IPv6 in URI brackets
        if (host.startsWith("[")) {
            return true;
        }
        // IPv4: all segments are digits
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            for (String part : parts) {
                if (!part.chars().allMatch(Character::isDigit)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

}
