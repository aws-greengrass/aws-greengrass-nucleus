/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import lombok.NonNull;
import software.amazon.awssdk.crt.http.HttpProxyOptions;
import software.amazon.awssdk.crt.io.ClientTlsContext;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class ProxyUtils {

    private static final AtomicReference<DeviceConfiguration> deviceConfiguration = new AtomicReference<>();

    private ProxyUtils() {
    }

    /**
     * <p>Returns <code>scheme</code> from the user provided proxy url of the format
     * <code>scheme://user:pass@host:port</code>.</p>
     *
     * <p><code>scheme</code> is required and must be one of <code>http</code>, <code>https</code>, or
     * <code>socks5</code></p>
     *
     * @param url User provided URL value from config
     * @return <code>scheme</code> in <code>scheme://user:pass@host:port</code>
     */
    public static String getSchemeFromProxyUrl(String url) {
        return URI.create(url).getScheme();
    }

    /**
     * <p>Returns <code>user:pass</code> from the user provided proxy url of the format
     * <code>scheme://user:pass@host:port</code>.</p>
     *
     * <p><code>user:pass</code> are optional</p>
     *
     * @param url User provided URL value from config
     * @return <code>user:pass</code> in <code>scheme://user:pass@host:port</code> or <code>null</code> if absent
     */
    public static String getAuthFromProxyUrl(String url) {
        return URI.create(url).getUserInfo();
    }

    /**
     * <p>Returns <code>host</code> from the user provided proxy url of the format
     * <code>scheme://user:pass@host:port</code>.</p>
     *
     * <p><code>host</code> is required</p>
     *
     * @param url User provided URL value from config
     * @return <code>host</code> in <code>scheme://user:pass@host:port</code>
     */
    public static String getHostFromProxyUrl(String url) {
        return URI.create(url).getHost();
    }

    private static int getDefaultPortForSchemeFromProxyUrl(String url) {
        String scheme = getSchemeFromProxyUrl(url);
        switch (scheme) {
            case "http":
                return 80;
            case "https":
                return 443;
            case "socks5":
                return 1080;
            default:
                return -1;
        }
    }

    /**
     * <p>Returns <code>port</code> from the user provided proxy url of the format
     * <code>scheme://user:pass@host:port</code>.</p>
     *
     * <p><code>port</code> is optional. If not provided, returns 80 for http, 443 for https, 1080 for socks5, or -1
     * for any other scheme.</p>
     *
     * @param url User provided URL value from config
     * @return <code>port</code> in <code>scheme://user:pass@host:port</code> or the default for the
     * <code>scheme</code>, -1 if <code>scheme</code> isn't recognized
     */
    public static int getPortFromProxyUrl(String url) {
        int userProvidedPort = URI.create(url).getPort();
        if (userProvidedPort != -1) {
            return userProvidedPort;
        }
        return getDefaultPortForSchemeFromProxyUrl(url);
    }

    /**
     * <p>If the username is provided in the proxy url (i.e. <code>user</code> in
     * <code>scheme://user:pass@host:port</code>), it is always returned.</p>
     *
     * <p>If the username is not provided in the proxy url, then the username config property is returned.</p>
     *
     * @param proxyUrl User specified proxy url
     * @param proxyUsername User specified proxy username
     * @return Username field for proxy basic authentication or null if not found in url or username config topics
     */
    public static String getProxyUsername(String proxyUrl, String proxyUsername) {
        String auth = getAuthFromProxyUrl(proxyUrl);
        if (Utils.isNotEmpty(auth)) {
            String[] tokens = auth.split(":");
            return tokens[0];
        }

        if (Utils.isNotEmpty(proxyUsername)) {
            return proxyUsername;
        }

        return null;
    }

    /**
     * <p>If the password is provided in the proxy url (i.e. <code>pass</code> in
     * <code>scheme://user:pass@host:port</code>), it is always returned.</p>
     *
     * <p>If the password is not provided in the proxy url, then the password config property is returned.</p>
     *
     * @param proxyUrl User specified proxy url
     * @param proxyPassword User specified proxy password
     * @return Password field for proxy basic authentication or null if not found in url or password config topics
     */
    public static String getProxyPassword(String proxyUrl, String proxyPassword) {
        String auth = getAuthFromProxyUrl(proxyUrl);
        if (Utils.isNotEmpty(auth)) {
            String[] tokens = auth.split(":");
            if (tokens.length > 1) {
                return tokens[1];
            }
        }

        if (Utils.isNotEmpty(proxyPassword)) {
            return proxyPassword;
        }

        return null;
    }

    /**
     * <p>Returns whether a proxy is configured in the nucleus device configuration.</p>
     *
     * @param deviceConfiguration contains user specified device values
     * @return true if a proxy is configured, false otherwise
     */
    public static boolean isProxyConfigured(DeviceConfiguration deviceConfiguration) {
        return Utils.isNotEmpty(deviceConfiguration.getProxyUrl());
    }

    /**
     * Provides a software.amazon.awssdk.crt.http.HttpProxyOptions object that can be used when building various
     * CRT library clients (like mqtt and http)
     *
     * @param deviceConfiguration contains user specified system proxy values
     * @param tlsContext contains TLS options for proxy connection if an HTTPS proxy is used
     * @return httpProxyOptions containing user proxy settings, if specified. If not, httpProxyOptions is null.
     */
    @Nullable
    public static HttpProxyOptions getHttpProxyOptions(DeviceConfiguration deviceConfiguration,
                                                       @NonNull ClientTlsContext tlsContext) {
        String proxyUrl = deviceConfiguration.getProxyUrl();
        if (Utils.isEmpty(proxyUrl)) {
            return null;
        }

        HttpProxyOptions httpProxyOptions = new HttpProxyOptions();
        httpProxyOptions.setHost(ProxyUtils.getHostFromProxyUrl(proxyUrl));
        httpProxyOptions.setPort(ProxyUtils.getPortFromProxyUrl(proxyUrl));
        httpProxyOptions.setConnectionType(HttpProxyOptions.HttpProxyConnectionType.Tunneling);

        if ("https".equalsIgnoreCase(getSchemeFromProxyUrl(proxyUrl))) {
            httpProxyOptions.setTlsContext(tlsContext);
        }

        String proxyUsername = getProxyUsername(proxyUrl, deviceConfiguration.getProxyUsername());
        if (Utils.isNotEmpty(proxyUsername)) {
            httpProxyOptions.setAuthorizationType(HttpProxyOptions.HttpProxyAuthorizationType.Basic);
            httpProxyOptions.setAuthorizationUsername(proxyUsername);
            httpProxyOptions
                    .setAuthorizationPassword(getProxyPassword(proxyUrl, deviceConfiguration.getProxyPassword()));
        }

        return httpProxyOptions;
    }

    /**
     * <p>Sets static proxy values to support easy client construction.</p>
     *
     * @param deviceConfiguration contains user specified system proxy values
     */
    public static void setDeviceConfiguration(DeviceConfiguration deviceConfiguration) {
        ProxyUtils.deviceConfiguration.set(deviceConfiguration);
    }

    /**
     * <p>Boilerplate for providing a proxy configured ApacheHttpClient to AWS SDK v2 client builders.</p>
     *
     * <p>If you need to customize the HttpClient, but still need proxy support, use <code>ProxyUtils
     * .getProxyConfiguration()</code></p>
     *
     * @return httpClient built with a ProxyConfiguration, if a proxy is configured, otherwise
     *         a default httpClient
     *
     * @deprecated Using this method in an SDK client builder would create a non-managed HTTP client, which does not
     *         close when the SDK client is closed. Recommend to use <code>ProxyUtils.getSdkHttpClientBuilder</code>
     *         instead.
     *
     * @see <a href="https://github.com/aws-greengrass/aws-greengrass-nucleus/pull/1368">depreacted reason</a>
     *
     */
    @Deprecated
    public static SdkHttpClient getSdkHttpClient() {
        return getSdkHttpClientBuilder().build();
    }

    /**
     * <p>Boilerplate for providing a proxy configured ApacheHttpClient builder to AWS SDK v2 client builders.</p>
     *
     * <p>To support HTTPS proxies and other scenarios, the HttpClient is configured with a trust manager
     * containing the root CAs from the nucleus configuration and the JVM's default root CAs.</p>
     *
     * <p>If you need to customize the HttpClient, but still need proxy support, use <code>ProxyUtils
     * .getProxyConfiguration()</code></p>
     *
     * @return httpClient builder with a ProxyConfiguration, if a proxy is configured, otherwise
     *         a default httpClient builder
     */
    public static ApacheHttpClient.Builder getSdkHttpClientBuilder() {
        ProxyConfiguration proxyConfiguration = getProxyConfiguration();

        if (proxyConfiguration != null) {
            return withClientSettings(ApacheHttpClient.builder())
                    .tlsTrustManagersProvider(ProxyUtils::createTrustManagers)
                    .proxyConfiguration(proxyConfiguration);
        }

        return withClientSettings(ApacheHttpClient.builder())
                .tlsTrustManagersProvider(ProxyUtils::createTrustManagers);
    }

    private static ApacheHttpClient.Builder withClientSettings(ApacheHttpClient.Builder builder) {
        DeviceConfiguration dc = deviceConfiguration.get();
        if (dc == null) {
            return builder;
        }
        Topics httpOptions = dc.getHttpClientOptions();

        long socketTimeoutMs = Coerce.toLong(httpOptions.find("socketTimeoutMs"));
        if (socketTimeoutMs > 0) {
            builder.socketTimeout(Duration.ofMillis(socketTimeoutMs));
        }
        long connectionTimeoutMs = Coerce.toLong(httpOptions.find("connectionTimeoutMs"));
        if (connectionTimeoutMs > 0) {
            builder.connectionTimeout(Duration.ofMillis(connectionTimeoutMs));
        }
        return builder;
    }

    private static TrustManager[] createTrustManagers() {
        try {
            List<X509Certificate> certificates = new ArrayList<>();
            Collections.addAll(certificates, getDefaultRootCertificates());

            DeviceConfiguration dc = deviceConfiguration.get();
            String rootCAPath = Coerce.toString(dc == null ? null : dc.getRootCAFilePath());
            if (Utils.isNotEmpty(rootCAPath) && Files.exists(Paths.get(rootCAPath))) {
                certificates.addAll(EncryptionUtils.loadX509Certificates(Paths.get(rootCAPath)));
            }

            KeyStore customKeyStore = KeyStore.getInstance("JKS");
            customKeyStore.load(null, null);

            // Populate a new KeyStore with the combined nucleus and default JVM root CA certificates.
            // When cert path validation is performed, the underlying SSLContext only uses the first X509TrustManager
            // it finds, so we must initialize a new TrustManager with one KeyStore containing all certificates.
            for (X509Certificate certificate : certificates) {
                String name = certificate.getSubjectX500Principal().getName("RFC2253");
                customKeyStore.setCertificateEntry(name, certificate);
            }

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
            trustManagerFactory.init(customKeyStore);
            return trustManagerFactory.getTrustManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to get trust manager", e);
        }
    }

    private static X509Certificate[] getDefaultRootCertificates() throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory defaultTrustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        defaultTrustManagerFactory.init((KeyStore) null);

        for (TrustManager tm : defaultTrustManagerFactory.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return ((X509TrustManager) tm).getAcceptedIssuers();
            }
        }
        return new X509Certificate[0];
    }

    private static String removeAuthFromProxyUrl(String proxyUrl) {
        URI uri = URI.create(proxyUrl);
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getHost());

        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }

        return sb.toString();
    }

    private static String addAuthToProxyUrl(String proxyUrl, String username, String password) {
        URI uri = URI.create(proxyUrl);
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(username).append(':').append(password).append('@')
                .append(uri.getHost());

        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }

        return sb.toString();
    }

    /**
     * <p>Boilerplate for providing a <code>ProxyConfiguration</code> to AWS SDK v2 <code>ApacheHttpClient</code>s.</p>
     *
     * @return ProxyConfiguration built with user proxy values or null if no proxy is configured (null is ignored in
     *     the SDK)
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    public static ProxyConfiguration getProxyConfiguration() {
        DeviceConfiguration dc = deviceConfiguration.get();
        if (dc == null) {
            return null;
        }
        String proxyUrl = dc.getProxyUrl();
        if (Utils.isEmpty(proxyUrl)) {
            return null;
        }

        // ProxyConfiguration throws an error if auth data is included in the url
        String urlWithoutAuth = removeAuthFromProxyUrl(proxyUrl);

        String proxyUsername = dc.getProxyUsername();
        String proxyPassword = dc.getProxyPassword();
        String username = getProxyUsername(proxyUrl, proxyUsername);
        String password = getProxyPassword(proxyUrl, proxyPassword);

        String proxyNoProxyAddresses = dc.getNoProxyAddresses();
        Set<String> nonProxyHosts = Collections.emptySet();
        if (Utils.isNotEmpty(proxyNoProxyAddresses)) {
            nonProxyHosts = Arrays.stream(proxyNoProxyAddresses.split(",")).collect(Collectors.toSet());
        }

        return ProxyConfiguration.builder()
                .endpoint(URI.create(urlWithoutAuth))
                .username(username)
                .password(password)
                .nonProxyHosts(nonProxyHosts)
                .build();
    }

    /**
     * <p>Provides a url for use in the ALL_PROXY, HTTP_PROXY, and HTTPS_PROXY environment variables.</p>
     *
     * <p>If auth info is provided in both the url and username/password fields, then the url value is used.</p>
     *
     * @param deviceConfiguration contains user specified system proxy values
     * @return the proxy url value or an empty string if no proxy is configured
     */
    public static String getProxyEnvVarValue(DeviceConfiguration deviceConfiguration) {
        String proxyUrl = deviceConfiguration.getProxyUrl();

        if (Utils.isEmpty(proxyUrl)) {
            return "";
        }

        String proxyUsername = deviceConfiguration.getProxyUsername();
        if (getAuthFromProxyUrl(proxyUrl) == null && Utils.isNotEmpty(proxyUsername)) {
            return addAuthToProxyUrl(proxyUrl, proxyUsername, deviceConfiguration.getProxyPassword());
        }
        return proxyUrl;
    }

    /**
     * <p>Provides a value for use in the NO_PROXY environment variable.</p>
     *
     * @param deviceConfiguration contains user specified system proxy values
     * @return localhost plus user provided values or an empty string if no proxy is configured
     */
    public static String getNoProxyEnvVarValue(DeviceConfiguration deviceConfiguration) {
        if (Utils.isEmpty(deviceConfiguration.getProxyUrl())) {
            return "";
        }

        if (Utils.isNotEmpty(deviceConfiguration.getNoProxyAddresses())) {
            return "localhost," + deviceConfiguration.getNoProxyAddresses();
        }
        return "localhost";
    }

}
