/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.http.HttpProxyOptions;
import software.amazon.awssdk.crt.io.ClientTlsContext;
import software.amazon.awssdk.crt.io.TlsContextOptions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class ProxyUtilsTest {

    @Mock
    DeviceConfiguration deviceConfiguration;

    @Test
    void testGetSchemeFromProxyUrl() {
        assertEquals("https", ProxyUtils.getSchemeFromProxyUrl("https://localhost"));
        assertEquals("http", ProxyUtils.getSchemeFromProxyUrl("http://localhost"));
        assertEquals("socks5", ProxyUtils.getSchemeFromProxyUrl("socks5://localhost"));
    }

    @Test
    void testGetAuthFromProxyUrl() {
        assertNull(ProxyUtils.getAuthFromProxyUrl("https://myproxy:8080"));
        assertEquals("user:password", ProxyUtils.getAuthFromProxyUrl("https://user:password@localhost:8080"));
        assertEquals("usernameOnly", ProxyUtils.getAuthFromProxyUrl("https://usernameOnly@localhost:8080"));
    }

    @Test
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    void testGetHostFromProxyUrl() {
        assertEquals("localhost", ProxyUtils.getHostFromProxyUrl("https://localhost"));
        assertEquals("192.168.0.1", ProxyUtils.getHostFromProxyUrl("https://192.168.0.1"));
        assertEquals("myproxy", ProxyUtils.getHostFromProxyUrl("https://myproxy:8080"));
        assertEquals("localhost", ProxyUtils.getHostFromProxyUrl("https://user:password@localhost:8080"));
        assertEquals("localhost", ProxyUtils.getHostFromProxyUrl("https://localhost/"));
    }

    @Test
    void testGetPortFromProxyUrl() {
        assertEquals(443, ProxyUtils.getPortFromProxyUrl("https://localhost"));
        assertEquals(80, ProxyUtils.getPortFromProxyUrl("http://localhost"));
        assertEquals(1080, ProxyUtils.getPortFromProxyUrl("socks5://localhost"));
        assertEquals(8080, ProxyUtils.getPortFromProxyUrl("https://myproxy:8080"));
        assertEquals(8080, ProxyUtils.getPortFromProxyUrl("https://user:password@localhost:8080"));
        assertEquals(8080, ProxyUtils.getPortFromProxyUrl("https://myproxy:8080/"));
    }

    @Test
    void testGetProxyUsername() {
        assertEquals("user@aws", ProxyUtils.getProxyUsername("https://user%40aws:password@localhost:8080", "test-user"));
        assertEquals("usernameOnly", ProxyUtils.getProxyUsername("https://usernameOnly@localhost:8080", "test-user"));
        assertEquals("test-user", ProxyUtils.getProxyUsername("https://myproxy:8080", "test-user"));
        assertNull(ProxyUtils.getProxyUsername("https://myproxy:8080", ""));
    }

    @Test
    void testGetProxyPassword() {
        assertEquals("password", ProxyUtils.getProxyPassword("https://user:password@localhost:8080", "itsasecret"));
        assertEquals("itsasecret", ProxyUtils.getProxyPassword("https://usernameOnly@localhost:8080", "itsasecret"));
        assertEquals("itsasecret", ProxyUtils.getProxyPassword("https://myproxy:8080", "itsasecret"));
        assertNull(ProxyUtils.getProxyPassword("https://myproxy:8080", ""));
    }

    @Test
    void testGetHttpProxyOptions() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://myproxy:8080");
        when(deviceConfiguration.getProxyUsername()).thenReturn("test-user");
        when(deviceConfiguration.getProxyPassword()).thenReturn("itsasecret");

        HttpProxyOptions httpProxyOptions = ProxyUtils.getHttpProxyOptions(deviceConfiguration,
                new ClientTlsContext(TlsContextOptions.createDefaultClient()));

        assertEquals("myproxy", httpProxyOptions.getHost());
        assertEquals(8080, httpProxyOptions.getPort());
        assertEquals(HttpProxyOptions.HttpProxyAuthorizationType.Basic, httpProxyOptions.getAuthorizationType());
        assertEquals("test-user", httpProxyOptions.getAuthorizationUsername());
        assertEquals("itsasecret", httpProxyOptions.getAuthorizationPassword());
    }

    @Test
    void testGetProxyEnvVarValue_passthroughWithAuth() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://test-user%40aws:itsasecret@myproxy:8080");
        assertEquals("https://test-user%40aws:itsasecret@myproxy:8080",
                ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_passthroughWithoutAuth() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://myproxy:8080");
        assertEquals("https://myproxy:8080", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_authInfoAddedToUrl() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://myproxy:8080");
        when(deviceConfiguration.getProxyUsername()).thenReturn("test-user@aws");
        when(deviceConfiguration.getProxyPassword()).thenReturn("itsasecret");
        assertEquals("https://test-user%40aws:itsasecret@myproxy:8080",
                ProxyUtils.getProxyEnvVarValue(deviceConfiguration));

        when(deviceConfiguration.getProxyPassword()).thenReturn(null);
        assertEquals("https://test-user%40aws@myproxy:8080",
                ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_authInfoNoOverride() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://test-user:itsasecret@myproxy:8080");
        when(deviceConfiguration.getProxyUsername()).thenReturn("different-test-user");
        assertEquals("https://test-user:itsasecret@myproxy:8080", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_noProxyConfigured() {
        when(deviceConfiguration.getProxyUrl()).thenReturn(null);
        assertEquals("", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_noPort_schemeDefaultAdded() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://myproxy");
        assertEquals("https://myproxy:443", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
        when(deviceConfiguration.getProxyUrl()).thenReturn("http://myproxy");
        assertEquals("http://myproxy:80", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
        when(deviceConfiguration.getProxyUrl()).thenReturn("socks5://myproxy");
        assertEquals("socks5://myproxy:1080", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_noPort_withAuthInUrl() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://test-user%40aws:itsasecret@myproxy");
        assertEquals("https://test-user%40aws:itsasecret@myproxy:443",
                ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_noPort_authInfoAddedToUrl() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://myproxy");
        when(deviceConfiguration.getProxyUsername()).thenReturn("test-user@aws");
        when(deviceConfiguration.getProxyPassword()).thenReturn("itsasecret");
        assertEquals("https://test-user%40aws:itsasecret@myproxy:443",
                ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_noPort_pathKept() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://myproxy/somethingA/somethingB");
        assertEquals("https://myproxy:443/somethingA/somethingB",
                ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_unknownScheme_unchanged() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("ftp://myproxy");
        assertEquals("ftp://myproxy", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetProxyEnvVarValue_hostNotParsable_unchangedWithoutWarning() {
        // A host containing '_' is not a valid server authority for java.net.URI, so getHost() is null and getPort()
        // is -1 even when a port is present. The url is passed through unchanged and no warning is logged.
        List<GreengrassLogMessage> warnings = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> {
            if ("WARN".equals(m.getLevel()) && ProxyUtils.class.getName().equals(m.getLoggerName())) {
                warnings.add(m);
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        try {
            when(deviceConfiguration.getProxyUrl()).thenReturn("http://my_proxy:3128");
            assertEquals("http://my_proxy:3128", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
            when(deviceConfiguration.getProxyUrl()).thenReturn("https://my_proxy");
            assertEquals("https://my_proxy", ProxyUtils.getProxyEnvVarValue(deviceConfiguration));
        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
        }
        assertTrue(warnings.isEmpty());
    }

    @Test
    void testGetNoProxyEnvVarValue_customValue() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://myproxy:8080");
        when(deviceConfiguration.getNoProxyAddresses()).thenReturn("other-local-domain,example.com");
        assertEquals("localhost,other-local-domain,example.com", ProxyUtils.getNoProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetNoProxyEnvVarValue_noValue() {
        when(deviceConfiguration.getProxyUrl()).thenReturn("https://myproxy:8080");
        when(deviceConfiguration.getNoProxyAddresses()).thenReturn(null);
        assertEquals("localhost", ProxyUtils.getNoProxyEnvVarValue(deviceConfiguration));
    }

    @Test
    void testGetNoProxyEnvVarValue_noProxyConfigured() {
        assertEquals("", ProxyUtils.getNoProxyEnvVarValue(deviceConfiguration));
    }

}
