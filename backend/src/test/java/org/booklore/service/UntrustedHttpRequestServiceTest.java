package org.booklore.service;

import org.booklore.security.RestrictedInetAddressMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UntrustedHttpRequestServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RestrictedInetAddressMatcher restrictedInetAddressMatcher;

    @InjectMocks
    private UntrustedHttpRequestService untrustedHttpRequestService;

    @Test
    @DisplayName("throws exception when response body is null")
    void request_nullBody_throwsException() {
        String exampleUrl = "http://1.1.1.1/image.jpg";
        ResponseEntity<byte[]> responseEntity = ResponseEntity.ok(null);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class)
        )).thenReturn(responseEntity);

        assertThrows(IOException.class, () -> untrustedHttpRequestService.request(exampleUrl));
    }

    @Test
    @DisplayName("throws exception on HTTP error status")
    void request_httpError_throwsException() {
        String url = "http://1.1.1.1/image.jpg";
        ResponseEntity<byte[]> responseEntity = ResponseEntity.notFound().build();
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(byte[].class)
        )).thenReturn(responseEntity);

        assertThrows(IOException.class, () -> untrustedHttpRequestService.request(url));
    }

    @Test
    @DisplayName("rewrites redirect URL to preserve hostname when CDN redirects to raw IP")
    void request_redirectToRawIp_rewritesUrlWithOriginalHost() throws IOException {
        String originalUrl = "http://example.com/cover.jpg";
        String cdnIpRedirect = "http://3.168.64.124/cover.jpg";
        byte[] responseBytes = new byte[]{0x01, 0x02, 0x03};

        ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(302)
                .header("Location", cdnIpRedirect).build();
        ResponseEntity<byte[]> finalResponse = ResponseEntity.ok(responseBytes);

        var urlCaptor = ArgumentCaptor.forClass(String.class);
        when(
                restTemplate.exchange(
                    urlCaptor.capture(),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(byte[].class)
        )).thenReturn(redirectResponse).thenReturn(finalResponse);

        untrustedHttpRequestService.request(originalUrl);

        assertEquals(originalUrl, urlCaptor.getAllValues().get(0));
        assertEquals("http://example.com/cover.jpg", urlCaptor.getAllValues().get(1));
    }

    @Test
    @DisplayName("preserves redirect path when rewriting raw IP URL back to hostname")
    void request_redirectToRawIpDifferentPath_preservesPath() throws IOException {
        String originalUrl = "http://example.com/images/cover.jpg";
        String cdnIpRedirect = "http://3.168.64.124/cdn/optimized/cover.jpg?token=abc";
        byte[] responseBytes = new byte[]{0x01, 0x02, 0x03};

        ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(302)
                .header("Location", cdnIpRedirect).build();
        ResponseEntity<byte[]> finalResponse = ResponseEntity.ok(responseBytes);

        var urlCaptor = ArgumentCaptor.forClass(String.class);
        when(restTemplate.exchange(
                urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
        )).thenReturn(redirectResponse).thenReturn(finalResponse);

        untrustedHttpRequestService.request(originalUrl);

        assertEquals("http://example.com/cdn/optimized/cover.jpg?token=abc", urlCaptor.getAllValues().get(1));
    }

    @Test
    @DisplayName("does not rewrite URL when redirect target is a hostname")
    void request_redirectToHostname_keepsRedirectUrl() throws IOException {
        String originalUrl = "http://example.com/cover.jpg";
        String hostnameRedirect = "http://www.example.com/cover.jpg";
        byte[] responseBytes = new byte[]{0x01, 0x02, 0x03};

        ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(301)
                .header("Location", hostnameRedirect).build();
        ResponseEntity<byte[]> finalResponse = ResponseEntity.ok(responseBytes);

        var urlCaptor = ArgumentCaptor.forClass(String.class);
        when(restTemplate.exchange(
                urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
        )).thenReturn(redirectResponse).thenReturn(finalResponse);

        untrustedHttpRequestService.request(originalUrl);

        assertEquals(hostnameRedirect, urlCaptor.getAllValues().get(1));
    }

    @Test
    @DisplayName("chain: hostname -> hostname -> raw IP uses last hostname for rewrite")
    void request_multipleRedirectsToRawIp_usesLastHostname() throws IOException {
        String originalUrl = "http://example.com/cover.jpg";
        String hostnameRedirect = "http://www.example.com/cover.jpg";
        String ipRedirect = "http://52.84.12.99/cover.jpg";
        byte[] responseBytes = new byte[]{0x01, 0x02, 0x03};

        ResponseEntity<byte[]> redirect1 = ResponseEntity.status(301)
                .header("Location", hostnameRedirect).build();
        ResponseEntity<byte[]> redirect2 = ResponseEntity.status(302)
                .header("Location", ipRedirect).build();
        ResponseEntity<byte[]> finalResponse = ResponseEntity.ok(responseBytes);

        var urlCaptor = ArgumentCaptor.forClass(String.class);
        when(restTemplate.exchange(
                urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
        )).thenReturn(redirect1).thenReturn(redirect2).thenReturn(finalResponse);

        untrustedHttpRequestService.request(originalUrl);

        assertEquals(originalUrl, urlCaptor.getAllValues().get(0));
        assertEquals(hostnameRedirect, urlCaptor.getAllValues().get(1));
        assertEquals("http://www.example.com/cover.jpg", urlCaptor.getAllValues().get(2));
    }

    @Test
    @DisplayName("throws exception when redirect exceeds max limit")
    void request_tooManyRedirects_throwsException() {
        String imageUrl = "http://1.1.1.1/cover.jpg";

        ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(302)
                .header("Location", "http://2.2.2.2/cover.jpg")
                .build();

        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
        )).thenReturn(redirectResponse);

        IOException ex = assertThrows(IOException.class, () ->
                untrustedHttpRequestService.request(imageUrl));
        assertTrue(ex.getMessage().contains("Too many redirects"));
    }

    @Test
    @DisplayName("throws exception when redirect has no Location header")
    void request_redirectWithoutLocation_throwsException() {
        String imageUrl = "http://1.1.1.1/image.jpg";

        ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(302).build();
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
        )).thenReturn(redirectResponse);

        IOException ex = assertThrows(IOException.class, () ->
                untrustedHttpRequestService.request(imageUrl));
        assertTrue(ex.getMessage().contains("Location"));
    }

    @Test
    @DisplayName("throws exception when address is restricted")
    void request_restrictedInetAddress_throwsException() throws Exception{
        String originalUrl = "http://example.com/cover.jpg";

        when(restrictedInetAddressMatcher.isRestrictedAddress(anyString())).thenReturn(true);

        assertThrows(SecurityException.class, () -> untrustedHttpRequestService.request(originalUrl));
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(byte[].class));
    }

    @Test
    @DisplayName("throws exception when address in redirect is restricted")
    void request_restrictedInetAddressInRedirect_throwsException() throws Exception {
        String originalUrl = "http://example.com/cover.jpg";
        String hostnameRedirect = "http://www.example2.com/cover.jpg";

        ResponseEntity<byte[]> redirectResponse = ResponseEntity.status(301)
                .header("Location", hostnameRedirect).build();

        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)
        )).thenReturn(redirectResponse);

        when(restrictedInetAddressMatcher.isRestrictedAddress(anyString()))
                .thenReturn(false)
                .thenReturn(true);

        assertThrows(SecurityException.class, () -> untrustedHttpRequestService.request(originalUrl));
        verify(restTemplate, atMostOnce()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(byte[].class));
    }

}
