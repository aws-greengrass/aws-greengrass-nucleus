/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.http.apache.internal.conn;

import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.protocol.HttpContext;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.http.apache.internal.net.SdkSocket;
import software.amazon.awssdk.http.apache.internal.net.SdkSslSocket;
import software.amazon.awssdk.utils.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Used to enforce the preferred TLS protocol during SSL handshake.
 *
 * Modified by Greengrass to add ALPN header.
 */
@SdkInternalApi
public class SdkTlsSocketFactory extends SSLConnectionSocketFactory {

    private static final Logger log = Logger.loggerFor(SdkTlsSocketFactory.class);
    private final SSLContext sslContext;

    public SdkTlsSocketFactory(final SSLContext sslContext, final HostnameVerifier hostnameVerifier) {
        super(sslContext, hostnameVerifier);
        if (sslContext == null) {
            throw new IllegalArgumentException(
                    "sslContext must not be null. " + "Use SSLContext.getDefault() if you are unsure.");
        }
        this.sslContext = sslContext;
    }

    @Override
    protected final void prepareSocket(final SSLSocket socket) {
        // BEGIN GG MODIFICATIONS
        try {
            SSLParameters params = new SSLParameters();
            params.setApplicationProtocols(new String[]{"x-amzn-http-ca", "http/1.1"});
            socket.setSSLParameters(params);
        } catch (NoSuchMethodError e) {
            log.warn(() -> "Unable to configure socket for ALPN. Ports other than 443 may still work."
                    + " Your Java version is more than 3 years outdated, "
                    + "update it to a version newer than Java 8u252.");
            // Java 8 did not launch with ALPN support, but it was backported in April 2020 with JDK8u252.
            // Catching the error here so that we can continue to work with older JDKs since the user may not
            // even require ALPN to work.
        }
        // END GG MODIFICATIONS

        log.debug(() -> String.format("socket.getSupportedProtocols(): %s, socket.getEnabledProtocols(): %s",
                Arrays.toString(socket.getSupportedProtocols()),
                Arrays.toString(socket.getEnabledProtocols())));
    }

    @Override
    public Socket connectSocket(
            final int connectTimeout,
            final Socket socket,
            final HttpHost host,
            final InetSocketAddress remoteAddress,
            final InetSocketAddress localAddress,
            final HttpContext context) throws IOException {
        log.trace(() -> String.format("Connecting to %s:%s", remoteAddress.getAddress(), remoteAddress.getPort()));

        Socket connectedSocket = super.connectSocket(connectTimeout, socket, host, remoteAddress, localAddress, context);

        if (connectedSocket instanceof SSLSocket) {
            return new SdkSslSocket((SSLSocket) connectedSocket);
        }

        return new SdkSocket(connectedSocket);
    }

}
