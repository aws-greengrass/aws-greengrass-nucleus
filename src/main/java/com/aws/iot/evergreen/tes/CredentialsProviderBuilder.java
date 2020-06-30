/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import software.amazon.awssdk.crt.auth.credentials.Credentials;
import software.amazon.awssdk.crt.auth.credentials.X509CredentialsProvider;
import software.amazon.awssdk.crt.auth.credentials.X509CredentialsProvider.X509CredentialsProviderBuilder;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.ClientTlsContext;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.TlsContextOptions;

import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

public class CredentialsProviderBuilder {
    private static final Logger LOGGER = LogManager.getLogger(CredentialsProviderBuilder.class);
    private X509CredentialsProviderBuilder x509builder;

    private final DeviceConfiguration deviceConfiguration;

    /**
     * Constructor.
     *
     * @param deviceConfiguration Device configuration helper getting cert and keys for mTLS
     * @throws DeviceConfigurationException When unable to initialize this manager.
     */
    @Inject
    CredentialsProviderBuilder(final DeviceConfiguration deviceConfiguration) throws DeviceConfigurationException {
        this.deviceConfiguration = deviceConfiguration;
        this.x509builder = initCredentialsProviderBuilder();
    }

    /**
     * Constructor for unit testing.
     *
     * @param deviceConfiguration Device configuration helper getting cert and keys for mTLS
     * @param x509builder x509Credentials provider builder
     * @throws DeviceConfigurationException When unable to initialize this manager.
     */
    CredentialsProviderBuilder(DeviceConfiguration deviceConfiguration, X509CredentialsProviderBuilder x509builder)
            throws DeviceConfigurationException {
        this.deviceConfiguration = deviceConfiguration;
        this.x509builder = x509builder;
    }

    private X509CredentialsProviderBuilder initCredentialsProviderBuilder()
            throws DeviceConfigurationException {
        DeviceConfiguration deviceConfiguration = this.deviceConfiguration;
        final String certPath = Coerce.toString(deviceConfiguration.getCertificateFilePath());
        final String keyPath = Coerce.toString(deviceConfiguration.getPrivateKeyFilePath());
        final String caPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        final String thingName = Coerce.toString(deviceConfiguration.getThingName());
        final String credEndpoint = Coerce.toString(deviceConfiguration.getIotCredentialEndpoint());
        try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
            HostResolver resolver = new HostResolver(eventLoopGroup);
            ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
            TlsContextOptions tlsCtxOptions = TlsContextOptions.createWithMtlsFromPath(certPath, keyPath)) {
            // TODO: Proxy support
            tlsCtxOptions.overrideDefaultTrustStoreFromPath(null, caPath);
            try (ClientTlsContext tlsContext = new ClientTlsContext(tlsCtxOptions)) {
                x509builder = new X509CredentialsProvider.X509CredentialsProviderBuilder()
                        .withClientBootstrap(clientBootstrap)
                        .withTlsContext(tlsContext)
                        .withEndpoint(credEndpoint)
                        .withThingName(thingName);
                return x509builder;
            }
        }
    }

    /**
     * Sets the role alias to fetch credentials through.
     * @param roleAlias name of the role alias to use
     */
    public void withRoleAlias(String roleAlias) {
        x509builder.withRoleAlias(roleAlias);
    }

    /**
     * To fetch credential.
     * @return AWS IoT credential
     * @throws AWSIotException when fetching credentials fails
     */
    public Credentials getCredentials() throws AWSIotException {
        Credentials credentials;
        try (X509CredentialsProvider provider = x509builder.build()) {
            CompletableFuture<Credentials> future = provider.getCredentials();
            credentials = future.get();
            if (credentials == null) {
                throw new AWSIotException("Unable to load AWS IOT credentials.");
            }
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Getting AWS IOT credentials failed with error {} ", e);
            throw new AWSIotException(e);
        }
        return credentials;
    }

    /**
     * To fetch expiry.
     * @return Expiration date of the credential
     * @throws AWSIotException when fetching expiration fails
     */
    public Date getCredentialsExpiration() throws AWSIotException {
        // TODO: update after device sdk implements getting expiration, temporarily use (now + 5 min)
        return new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5));
    }
}
