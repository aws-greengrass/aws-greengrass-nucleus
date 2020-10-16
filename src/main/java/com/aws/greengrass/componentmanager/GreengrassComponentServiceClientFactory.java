/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.AWSEvergreenClientBuilder;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.EncryptionUtils;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import lombok.Getter;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Named;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

@Getter
@SuppressWarnings("PMD.ConfusingTernary")
public class GreengrassComponentServiceClientFactory {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                            "([a-z0-9+/=\\r\\n]+)" +                       // Base64 text
                            "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",            // Footer
                    CASE_INSENSITIVE);

    public static final String CONTEXT_COMPONENT_SERVICE_ENDPOINT = "greengrassServiceEndpoint";
    private static final Logger logger = LogManager.getLogger(GreengrassComponentServiceClientFactory.class);

    private final AWSEvergreen cmsClient;

    /**
     * Constructor with custom endpoint/region configuration.
     *
     * @param greengrassServiceEndpoint String containing service endpoint
     * @param deviceConfiguration       Device configuration
     * @throws TLSAuthException Cannot initialize mutual TLS connection socket
     */
    @Inject
    public GreengrassComponentServiceClientFactory(
            @Named(CONTEXT_COMPONENT_SERVICE_ENDPOINT) String greengrassServiceEndpoint,
            DeviceConfiguration deviceConfiguration) throws TLSAuthException {

        ClientConfiguration clientConfiguration = ProxyUtils.getClientConfiguration();
        try {
            configureClientMutualTLS(clientConfiguration, deviceConfiguration);
        } catch (TLSAuthException e) {
            logger.atWarn("configure-greengrass-mutual-auth")
                    .log("Error during configure greengrass client mutual auth", e);
        }
        AWSEvergreenClientBuilder clientBuilder =
                AWSEvergreenClientBuilder.standard().withClientConfiguration(clientConfiguration);
        String region = Coerce.toString(deviceConfiguration.getAWSRegion());

        if (!Utils.isEmpty(region)) {
            if (!Utils.isEmpty(greengrassServiceEndpoint)) {
                // Region and endpoint are both required when updating endpoint config
                logger.atInfo("initialize-greengrass-client").addKeyValue("service-endpoint", greengrassServiceEndpoint)
                        .addKeyValue("service-region", region).log();
                EndpointConfiguration endpointConfiguration =
                        new EndpointConfiguration(greengrassServiceEndpoint, region);
                clientBuilder.withEndpointConfiguration(endpointConfiguration);
            } else {
                // This section is to override default region if needed
                logger.atInfo("initialize-greengrass-client").addKeyValue("service-region", region).log();
                clientBuilder.withRegion(region);
            }
        }

        this.cmsClient = clientBuilder.build();
    }

    private void configureClientMutualTLS(ClientConfiguration clientConfiguration,
                                          DeviceConfiguration deviceConfiguration) throws TLSAuthException {
        if (clientConfiguration == null) {
            return;
        }
        String certificatePath = Coerce.toString(deviceConfiguration.getCertificateFilePath());
        String privateKeyPath = Coerce.toString(deviceConfiguration.getPrivateKeyFilePath());
        String rootCAPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        if (Utils.isEmpty(certificatePath) || Utils.isEmpty(privateKeyPath) || Utils.isEmpty(rootCAPath)) {
            return;
        }

        TrustManager[] trustManagers = createTrustManagers(rootCAPath);
        KeyManager[] keyManagers = createKeyManagers(privateKeyPath, certificatePath);
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(keyManagers, trustManagers, null);
        } catch (GeneralSecurityException e) {
            throw new TLSAuthException("Failed to initialize TLS context", e);
        }

        SSLConnectionSocketFactory sslConnectionSocketFactory =
                new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
        clientConfiguration.getApacheHttpClientConfig().setSslSocketFactory(sslConnectionSocketFactory);
    }

    private TrustManager[] createTrustManagers(String rootCAPath) throws TLSAuthException {
        try {
            List<X509Certificate> trustCertificates = EncryptionUtils.loadX509Certificates(rootCAPath);

            KeyStore tmKeyStore = KeyStore.getInstance("JKS");
            tmKeyStore.load(null, null);
            for (X509Certificate certificate : trustCertificates) {
                X500Principal principal = certificate.getSubjectX500Principal();
                String name = principal.getName("RFC2253");
                tmKeyStore.setCertificateEntry(name, certificate);
            }
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
            trustManagerFactory.init(tmKeyStore);
            return trustManagerFactory.getTrustManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new TLSAuthException("Failed to get trust manager", e);
        }
    }

    private KeyManager[] createKeyManagers(String privateKeyPath, String certificatePath) throws TLSAuthException {
        try {
            List<X509Certificate> certificateChain = EncryptionUtils.loadX509Certificates(certificatePath);

            PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null);
            keyStore.setKeyEntry("private-key", privateKey, "password".toCharArray(),
                    certificateChain.stream().toArray(Certificate[]::new));

            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, "password".toCharArray());
            return keyManagerFactory.getKeyManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new TLSAuthException("Failed to get key manager", e);
        }
    }
}
