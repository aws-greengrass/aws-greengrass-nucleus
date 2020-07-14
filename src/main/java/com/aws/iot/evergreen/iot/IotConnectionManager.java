/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.iot;

import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import software.amazon.awssdk.crt.http.HttpClientConnection;
import software.amazon.awssdk.crt.http.HttpClientConnectionManager;
import software.amazon.awssdk.crt.http.HttpClientConnectionManagerOptions;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContext;
import software.amazon.awssdk.crt.io.TlsContextOptions;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Inject;

public class IotConnectionManager implements Closeable {
    // TODO: Move Iot related classes to a central location
    private static final Logger LOGGER = LogManager.getLogger(IotConnectionManager.class);
    // TODO: ALPN support, and validate how does it work if port is also part of URL
    private static final int IOT_PORT = 8443;
    // Max wait time for device to establish mTLS connection with IOT core
    private static final long TIMEOUT_FOR_CONNECTION_SETUP_SECONDS = Duration.ofMinutes(1).getSeconds();
    private final HttpClientConnectionManager connManager;

    /**
     * Constructor.
     *
     * @param deviceConfiguration Device configuration helper getting cert and keys for mTLS
     * @throws DeviceConfigurationException When unable to initialize this manager.
     */
    @Inject
    public IotConnectionManager(final DeviceConfiguration deviceConfiguration) throws DeviceConfigurationException {
        this.connManager = initConnectionManager(deviceConfiguration);
    }

    private HttpClientConnectionManager initConnectionManager(DeviceConfiguration deviceConfiguration)
            throws DeviceConfigurationException {
        final String certPath = Coerce.toString(deviceConfiguration.getCertificateFilePath());
        final String keyPath = Coerce.toString(deviceConfiguration.getPrivateKeyFilePath());
        final String caPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
             HostResolver resolver = new HostResolver(eventLoopGroup);
             ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
             TlsContextOptions tlsCtxOptions = TlsContextOptions.createWithMtlsFromPath(certPath, keyPath)) {
            // TODO: Proxy support, ALPN support. Reuse connections across kernel
            tlsCtxOptions.overrideDefaultTrustStoreFromPath(null, caPath);
            return HttpClientConnectionManager
                    .create(new HttpClientConnectionManagerOptions().withClientBootstrap(clientBootstrap)
                            .withSocketOptions(new SocketOptions()).withTlsContext(new TlsContext(tlsCtxOptions))
                            .withPort(IOT_PORT).withUri(URI.create(
                                    "https://" + Coerce.toString(deviceConfiguration.getIotCredentialEndpoint()))));
        }
    }

    /**
     * Get a connection object for sending requests.
     *
     * @return {@link HttpClientConnection}
     * @throws AWSIotException when getting a connection from underlying manager fails.
     */
    public HttpClientConnection getConnection() throws AWSIotException {
        try {
            return connManager.acquireConnection().get(TIMEOUT_FOR_CONNECTION_SETUP_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Getting connection failed for endpoint {} with error {} ", connManager.getUri(), e);
            throw new AWSIotException(e);
        }
    }

    /**
     * Get the host string underlying connection manager.
     *
     * @return Host string to be used in HTTP Host headers
     */
    public String getHost() {
        return connManager.getUri().getHost();
    }

    /**
     * Clean up underlying connections and close gracefully.
     */
    @Override
    public void close() {
        // TODO: tear down connections gracefully
    }

}
