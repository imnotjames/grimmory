package org.booklore.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

public class RestrictedInetAddressMatcherTest {
    private final RestrictedInetAddressMatcher restrictedInetAddressMatcher = new RestrictedInetAddressMatcher();

    @ParameterizedTest
    @ValueSource(strings = {
            "192.168.1.1",
            "192.0.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "198.18.0.1",
            "255.255.255.255",
            "64:ff9b::c0a8:0101",
            "2002:c0a8:0101::1",
            "2001:0000:4136:e378:8000:63bf:3f57:fefe",
            "0.0.0.0",
            "0.0.0.1",
    })
    void isRestrictedAddress_handlesLocalAddresses(String address) throws Exception {
        assertThat(restrictedInetAddressMatcher.isRestrictedAddress(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4.2.2.1",
            "8.8.8.8",
            "2606:4700::6810:85e5",
            "2607:f8b0:4007:804::200e",
    })
    void isRestrictedAddress_handlesKnownSafeExternalAddresses(String address) throws Exception {
        assertThat(restrictedInetAddressMatcher.isRestrictedAddress(InetAddress.getByName(address))).isFalse();
    }

    @Test
    void isRestrictedAddress_byHostThrowsExceptionWhenNoResolvedHosts() throws Exception {
        var addresses = new InetAddress[]{};

        try (var inetMock = mockStatic(InetAddress.class)) {
            inetMock.when(() -> InetAddress.getAllByName("example.com")).thenReturn(addresses);

            var exception = assertThrows(IOException.class, () -> restrictedInetAddressMatcher.isRestrictedAddress("example.com"));
            assertThat(exception).hasMessage("Unable to resolve host");
        }
    }

    @Test
    void isRestrictedAddress_byHostHandlesUnsafeAddress() throws Exception {
        var addresses = new InetAddress[]{
                InetAddress.getByName("0.0.0.0"),
        };

        try (var inetMock = mockStatic(InetAddress.class)) {
            inetMock.when(() -> InetAddress.getAllByName("example.com")).thenReturn(addresses);

            assertThat(restrictedInetAddressMatcher.isRestrictedAddress("example.com")).isTrue();
        }
    }

    @Test
    void isRestrictedAddress_byHostHandlesKnownSafeAddress() throws Exception {
        var addresses = new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
        };

        try (var inetMock = mockStatic(InetAddress.class)) {
            inetMock.when(() -> InetAddress.getAllByName("example.com")).thenReturn(addresses);

            assertThat(restrictedInetAddressMatcher.isRestrictedAddress("example.com")).isFalse();
        }
    }

    @Test
    void isRestrictedAddress_byHostHandlesMixOfSafeAndUnsafeAddresses() throws Exception {
        var addresses = new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("0.0.0.0"),
                InetAddress.getByName("1.1.1.1"),
        };

        try (var inetMock = mockStatic(InetAddress.class)) {
            inetMock.when(() -> InetAddress.getAllByName("example.com")).thenReturn(addresses);

            assertThat(restrictedInetAddressMatcher.isRestrictedAddress("example.com")).isTrue();
        }
    }
}
