/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.http.HttpProxyOptions;
import software.amazon.awssdk.crt.io.ClientTlsContext;
import software.amazon.awssdk.crt.io.TlsContextOptions;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class ProxyUtilsTest {

    private static final char[] KEYSTORE_PASSWORD = "password".toCharArray();

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

    @Test
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    void GIVEN_first_resolved_address_unreachable_WHEN_https_request_THEN_next_address_is_used(@TempDir Path tempDir)
            throws Exception {
        Pair<Path, KeyPair> certAndKey =
                EncryptionUtilsTest.generateCertificateFile(2048, false, tempDir.resolve("cert.der"), false);
        Certificate cert;
        try (InputStream in = Files.newInputStream(certAndKey.getLeft())) {
            cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", certAndKey.getRight().getPrivate(), KEYSTORE_PASSWORD,
                new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        // ff0e::1 is multicast, so a TCP connect to it fails with "Network is unreachable" (ENETUNREACH) on any
        // host, like an AAAA record does on an IPv4-only host.
        InetAddress[] resolved = {InetAddress.getByName("ff0e::1"), InetAddress.getByName("127.0.0.1")};
        ProxyUtils.setDeviceConfiguration(null);
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             SdkHttpClient client = ProxyUtils.getSdkHttpClientBuilder()
                     .dnsResolver(host -> resolved)
                     .tlsTrustManagersProvider(tmf::getTrustManagers)
                     .build()) {
            CompletableFuture<Void> serverDone = CompletableFuture.runAsync(() -> respondOk(server));
            // The certificate's only name is its CN, ROOT, so the request uses it as the host.
            SdkHttpFullRequest request = SdkHttpFullRequest.builder().method(SdkHttpMethod.GET)
                    .uri(URI.create("https://root:" + server.getLocalPort() + "/")).build();

            HttpExecuteResponse response =
                    client.prepareRequest(HttpExecuteRequest.builder().request(request).build()).call();

            assertEquals(200, response.httpResponse().statusCode());
            serverDone.get(10, TimeUnit.SECONDS);
        }
    }

    private static void respondOk(SSLServerSocket server) {
        try (Socket socket = server.accept();
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            int matched = 0;
            byte[] endOfHeaders = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            while (matched < endOfHeaders.length) {
                int b = in.read();
                if (b < 0) {
                    return;
                }
                matched = b == endOfHeaders[matched] ? matched + 1 : b == endOfHeaders[0] ? 1 : 0;
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
