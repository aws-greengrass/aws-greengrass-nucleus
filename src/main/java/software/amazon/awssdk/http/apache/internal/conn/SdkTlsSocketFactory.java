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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    /**
     * {@inheritDoc} Used to enforce the preferred TLS protocol during SSL handshake.
     */
    @Override
    protected final void prepareSocket(final SSLSocket socket) {
        // BEGIN GG MODIFICATIONS
        try {
            SSLParameters params = new SSLParameters();
            params.setApplicationProtocols(new String[]{"x-amzn-http-ca", "http/1.1"});
            socket.setSSLParameters(params);
        } catch (NoSuchMethodError e) {
            log.debug(() -> "Unable to configure socket for ALPN. Ports other than 443 may not work.");
            // Java 8 did not launch with ALPN support, but it was backported in April 2020 with JDK8u252.
            // Catching the error here so that we can continue to work with older JDKs since the user may not
            // even require ALPN to work.
        }
        // END GG MODIFICATIONS

        String[] supported = socket.getSupportedProtocols();
        String[] enabled = socket.getEnabledProtocols();
        log.debug(() -> String.format("socket.getSupportedProtocols(): %s, socket.getEnabledProtocols(): %s",
                                      Arrays.toString(supported),
                                      Arrays.toString(enabled)));
        List<String> target = new ArrayList<>();
        if (supported != null) {
            // Append the preferred protocols in descending order of preference
            // but only do so if the protocols are supported
            TlsProtocol[] values = TlsProtocol.values();
            for (TlsProtocol value : values) {
                String pname = value.getProtocolName();
                if (existsIn(pname, supported)) {
                    target.add(pname);
                }
            }
        }
        if (enabled != null) {
            // Append the rest of the already enabled protocols to the end
            // if not already included in the list
            for (String pname : enabled) {
                if (!target.contains(pname)) {
                    target.add(pname);
                }
            }
        }
        if (target.size() > 0) {
            String[] enabling = target.toArray(new String[0]);
            socket.setEnabledProtocols(enabling);
            log.debug(() -> "TLS protocol enabled for SSL handshake: " + Arrays.toString(enabling));
        }
    }

    /**
     * Returns true if the given element exists in the given array; false otherwise.
     */
    private boolean existsIn(String element, String[] a) {
        for (String s : a) {
            if (element.equals(s)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Socket connectSocket(
            final int connectTimeout,
            final Socket socket,
            final HttpHost host,
            final InetSocketAddress remoteAddress,
            final InetSocketAddress localAddress,
            final HttpContext context) throws IOException {
        log.debug(() -> String.format("Connecting to %s:%s", remoteAddress.getAddress(), remoteAddress.getPort()));

        Socket connectedSocket = super.connectSocket(connectTimeout, socket, host, remoteAddress, localAddress, context);

        if (connectedSocket instanceof SSLSocket) {
            return new SdkSslSocket((SSLSocket) connectedSocket);
        }

        return new SdkSocket(connectedSocket);
    }

}
