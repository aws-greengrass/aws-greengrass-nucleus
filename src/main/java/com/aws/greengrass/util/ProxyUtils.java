/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.util;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.ProxyAuthenticationMethod;
import com.aws.greengrass.deployment.DeviceConfiguration;
import software.amazon.awssdk.crt.http.HttpProxyOptions;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public final class ProxyUtils {

    public static final String GREENGRASS_PROXY_URL_KEY = "GREENGRASS_PROXY_URL";
    public static final String GREENGRASS_PROXY_USERNAME_KEY = "GREENGRASS_PROXY_USERNAME";
    public static final String GREENGRASS_PROXY_PASSWORD_KEY = "GREENGRASS_PROXY_PASSWORD";
    public static final String GREENGRASS_PROXY_NOPROXYADDRESSES_KEY = "GREENGRASS_PROXY_NOPROXYADDRESSES";

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
     * Provides an software.amazon.awssdk.crt.http.HttpProxyOptions object that can be used when building various
     * CRT library clients (like mqtt and http)
     *
     * @param deviceConfiguration contains user specified system proxy values
     * @return httpProxyOptions containing user proxy settings, if specified. If not, httpProxyOptions is null.
     */
    public static HttpProxyOptions getHttpProxyOptions(DeviceConfiguration deviceConfiguration) {
        HttpProxyOptions httpProxyOptions = null;

        String proxyUrl = deviceConfiguration.getProxyUrl();
        String proxyUsername = getProxyUsername(deviceConfiguration.getProxyUrl(),
                deviceConfiguration.getProxyUsername());

        if (Utils.isNotEmpty(proxyUrl)) {
            httpProxyOptions = new HttpProxyOptions();
            httpProxyOptions.setHost(ProxyUtils.getHostFromProxyUrl(proxyUrl));
            httpProxyOptions.setPort(ProxyUtils.getPortFromProxyUrl(proxyUrl));

            if (Utils.isNotEmpty(proxyUsername)) {
                httpProxyOptions.setAuthorizationType(HttpProxyOptions.HttpProxyAuthorizationType.Basic);
                httpProxyOptions.setAuthorizationUsername(proxyUsername);
                httpProxyOptions.setAuthorizationPassword(getProxyPassword(deviceConfiguration.getProxyUrl(),
                        deviceConfiguration.getProxyPassword()));
            }
        }

        return httpProxyOptions;
    }

    /**
     * <p>Sets the GREENGRASS_PROXY_* system properties with values from the device configuration.</p>
     *
     * @param deviceConfiguration contains user specified system proxy values
     */
    public static void setSystemProxyProperties(DeviceConfiguration deviceConfiguration) {
        if (deviceConfiguration == null) {
            return;
        }

        System.setProperty(GREENGRASS_PROXY_URL_KEY, deviceConfiguration.getProxyUrl());
        System.setProperty(GREENGRASS_PROXY_USERNAME_KEY, deviceConfiguration.getProxyUsername());
        System.setProperty(GREENGRASS_PROXY_PASSWORD_KEY, deviceConfiguration.getProxyPassword());
        System.setProperty(GREENGRASS_PROXY_NOPROXYADDRESSES_KEY, deviceConfiguration.getNoProxyAddresses());
    }

    /**
     * <p>Boilerplate for providing a proxy configured ApacheHttpClient to AWS SDK v2 client builders.</p>
     *
     * <p>If you need to customize the HttpClient, but still need proxy support, use <code>ProxyUtils
     * .getProxyConfiguration()</code></p>
     *
     * @return httpClient built with a ProxyConfiguration or null if no proxy is configured (null is ignored in AWS
     *     SDK clients)
     */
    public static SdkHttpClient getSdkHttpClient() {
        ProxyConfiguration proxyConfiguration = getProxyConfiguration();
        SdkHttpClient httpClient = null;

        if (proxyConfiguration != null) {
            httpClient = ApacheHttpClient.builder()
                    .proxyConfiguration(proxyConfiguration)
                    .build();
        }

        return httpClient;
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

    /**
     * <p>Boilerplate for providing a <code>ProxyConfiguration</code> to AWS SDK v2 <code>ApacheHttpClient</code>s.</p>
     *
     * @return ProxyConfiguration built with user proxy values or null if no proxy is configured (null is ignored in
     *     the SDK)
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    public static ProxyConfiguration getProxyConfiguration() {
        String url = System.getProperty(GREENGRASS_PROXY_URL_KEY);
        String usernameFromConfig = System.getProperty(GREENGRASS_PROXY_USERNAME_KEY);
        String passwordFromConfig = System.getProperty(GREENGRASS_PROXY_PASSWORD_KEY);
        String noProxyAddresses = System.getProperty(GREENGRASS_PROXY_NOPROXYADDRESSES_KEY);

        if (Utils.isEmpty(url)) {
            return null;
        }

        // ProxyConfiguration throws an error if auth data is included in the url
        String urlWithoutAuth = removeAuthFromProxyUrl(url);

        String username = getProxyUsername(url, usernameFromConfig);
        String password = getProxyPassword(url, passwordFromConfig);

        Set<String> nonProxyHosts = Collections.emptySet();
        if (Utils.isNotEmpty(noProxyAddresses)) {
            nonProxyHosts = Arrays.stream(noProxyAddresses.split(",")).collect(Collectors.toSet());
        }

        return ProxyConfiguration.builder()
                .endpoint(URI.create(urlWithoutAuth))
                .username(username)
                .password(password)
                .nonProxyHosts(nonProxyHosts)
                .build();
    }

    /**
     * <p>Returns a <code>com.amazonaws.Protocol</code> for configuring a proxy in v1 of the AWS SDK.</p>
     *
     * <p>SOCKS is not supported (SDK limitation)</p>
     *
     * @param url User provided URL value from config
     * @return Protocol, either HTTP or HTTPS or null for all other values
     */
    public static Protocol getProtocolFromProxyUrl(String url) {
        String scheme = getSchemeFromProxyUrl(url);
        switch (scheme) {
            case "http":
                return Protocol.HTTP;
            case "https":
                return Protocol.HTTPS;
            default:
                return null;
        }
    }

    /**
     * <p>Boilerplate for providing a <code>ClientConfiguration</code> with proxy values to AWS SDK v1 client
     * builders.</p>
     *
     * <p>SOCKS is not supported (SDK limitation)</p>
     *
     * @return ClientConfiguration built with proxy values, if present
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    public static ClientConfiguration getClientConfiguration() {
        final String url = System.getProperty(GREENGRASS_PROXY_URL_KEY);
        final String usernameFromConfig = System.getProperty(GREENGRASS_PROXY_USERNAME_KEY);
        final String passwordFromConfig = System.getProperty(GREENGRASS_PROXY_PASSWORD_KEY);
        final String noProxyAddresses = System.getProperty(GREENGRASS_PROXY_NOPROXYADDRESSES_KEY);

        ClientConfiguration clientConfiguration = new ClientConfiguration();

        if (Utils.isEmpty(url)) {
            return clientConfiguration;
        }

        clientConfiguration.setProxyProtocol(getProtocolFromProxyUrl(url));
        clientConfiguration.setProxyHost(getHostFromProxyUrl(url));
        clientConfiguration.setProxyPort(getPortFromProxyUrl(url));

        String username = getProxyUsername(url, usernameFromConfig);
        if (Utils.isNotEmpty(username)) {
            clientConfiguration.setProxyAuthenticationMethods(Arrays.asList(ProxyAuthenticationMethod.BASIC));
            clientConfiguration.setProxyUsername(username);
            clientConfiguration.setProxyPassword(getProxyPassword(url, passwordFromConfig));
        }

        if (Utils.isNotEmpty(noProxyAddresses)) {
            // The SDK expects the same delimiter as the http.nonProxyHosts property (i.e. |)
            clientConfiguration.setNonProxyHosts(noProxyAddresses.replace(",", "|"));
        }

        return clientConfiguration;
    }

}
