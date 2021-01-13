/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.EncryptionUtils;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.RegionUtils;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import lombok.Getter;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2.GreengrassV2Client;
import software.amazon.awssdk.services.greengrassv2.GreengrassV2ClientBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.inject.Inject;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;

@Getter
@SuppressWarnings("PMD.ConfusingTernary")
public class GreengrassComponentServiceClientFactory {

    private static final Logger logger = LogManager.getLogger(GreengrassComponentServiceClientFactory.class);

    private GreengrassV2Client cmsClient;

    /**
     * Constructor with custom endpoint/region configuration.
     *
     * @param deviceConfiguration       Device configuration
     */
    @Inject
    public GreengrassComponentServiceClientFactory(DeviceConfiguration deviceConfiguration) {
        configureClient(deviceConfiguration);
        deviceConfiguration.onAnyChange((what, node) -> {
            if (validString(node, DEVICE_PARAM_AWS_REGION) || validPath(node, DEVICE_PARAM_ROOT_CA_PATH) || validPath(
                    node, DEVICE_PARAM_CERTIFICATE_FILE_PATH) || validPath(node, DEVICE_PARAM_PRIVATE_KEY_PATH)) {
                configureClient(deviceConfiguration);
            }
        });
    }

    private boolean validString(Node node, String key) {
        return node != null && node.childOf(key) && Utils.isNotEmpty(Coerce.toString(node));
    }

    private boolean validPath(Node node, String key) {
        return validString(node, key) && Files.exists(Paths.get(key));
    }

    private void configureClient(DeviceConfiguration deviceConfiguration) {
        ApacheHttpClient.Builder httpClient = ProxyUtils.getSdkHttpClientBuilder();
        httpClient = httpClient == null ? ApacheHttpClient.builder() : httpClient;
        try {
            configureClientMutualTLS(httpClient, deviceConfiguration);
        } catch (TLSAuthException e) {
            logger.atWarn("configure-greengrass-mutual-auth")
                    .log("Error during configure greengrass client mutual auth", e);
        }
        GreengrassV2ClientBuilder clientBuilder = GreengrassV2Client.builder()
                // Use an empty credential provider because our requests don't need SigV4
                // signing, as they are going through IoT Core instead
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .httpClient(httpClient.build())
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(RetryMode.STANDARD).build());
        String region = Coerce.toString(deviceConfiguration.getAWSRegion());

        if (!Utils.isEmpty(region)) {
            String greengrassServiceEndpoint = getGreengrassServiceEndpoint(deviceConfiguration);
            if (!Utils.isEmpty(greengrassServiceEndpoint)) {
                // Region and endpoint are both required when updating endpoint config
                logger.atInfo("initialize-greengrass-client").addKeyValue("service-endpoint", greengrassServiceEndpoint)
                        .addKeyValue("service-region", region).log();
                clientBuilder.endpointOverride(URI.create(greengrassServiceEndpoint));
                clientBuilder.region(Region.of(region));
            } else {
                // This section is to override default region if needed
                logger.atInfo("initialize-greengrass-client").addKeyValue("service-region", region).log();
                clientBuilder.region(Region.of(region));
            }
        }

        this.cmsClient = clientBuilder.build();
    }

    private String getGreengrassServiceEndpoint(DeviceConfiguration deviceConfiguration) {
        IotSdkClientFactory.EnvironmentStage stage;
        try {
            stage = IotSdkClientFactory.EnvironmentStage
                    .fromString(Coerce.toString(deviceConfiguration.getEnvironmentStage()));
        } catch (InvalidEnvironmentStageException e) {
            logger.atError().setCause(e).log("Caught exception while parsing Nucleus args");
            throw new RuntimeException(e);
        }
        return RegionUtils.getGreengrassDataPlaneEndpoint(Coerce.toString(deviceConfiguration.getAWSRegion()), stage,
                Coerce.toInt(deviceConfiguration.getGreengrassDataPlanePort()));
    }

    private void configureClientMutualTLS(ApacheHttpClient.Builder httpBuilder,
                                          DeviceConfiguration deviceConfiguration) throws TLSAuthException {
        String certificatePath = Coerce.toString(deviceConfiguration.getCertificateFilePath());
        String privateKeyPath = Coerce.toString(deviceConfiguration.getPrivateKeyFilePath());
        String rootCAPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        if (Utils.isEmpty(certificatePath) || Utils.isEmpty(privateKeyPath) || Utils.isEmpty(rootCAPath)) {
            return;
        }

        TrustManager[] trustManagers = createTrustManagers(rootCAPath);
        KeyManager[] keyManagers = createKeyManagers(privateKeyPath, certificatePath);

        httpBuilder.tlsKeyManagersProvider(() -> keyManagers).tlsTrustManagersProvider(() -> trustManagers);
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
            keyStore.setKeyEntry("private-key", privateKey, null, certificateChain.toArray(new Certificate[0]));

            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, null);
            return keyManagerFactory.getKeyManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new TLSAuthException("Failed to get key manager", e);
        }
    }
}
