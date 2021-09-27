/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.security.exceptions.ServiceUnavailableException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.EncryptionUtils;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.RegionUtils;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;

public final class ClientConfigurationUtils {

    private static final Logger logger = LogManager.getLogger(ClientConfigurationUtils.class);
    // retry 10 times with exponential backoff of max interval 1 mins
    // leave enough time for the crypto key service to be available
    private static final RetryUtils.RetryConfig SECURITY_SERVICE_RETRY_CONFIG = RetryUtils.RetryConfig.builder()
            .retryableExceptions(Collections.singletonList(ServiceUnavailableException.class)).build();
    private final SecurityService securityService;

    @Inject
    public ClientConfigurationUtils(SecurityService securityService) {
        this.securityService = securityService;
    }

    /**
     * Get the greengrass service endpoint.
     *
     * @param deviceConfiguration {@link DeviceConfiguration}
     * @return service end point
     */
    public String getGreengrassServiceEndpoint(DeviceConfiguration deviceConfiguration) {
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

    /**
     * Configure the http client builder with the required certificates for the mutual auth connection.
     *
     * @param deviceConfiguration {@link DeviceConfiguration}
     * @return configured http client
     */
    public ApacheHttpClient.Builder getConfiguredClientBuilder(DeviceConfiguration deviceConfiguration) {
        ApacheHttpClient.Builder httpClient = ProxyUtils.getSdkHttpClientBuilder();

        try {
            configureClientMutualTLS(httpClient, deviceConfiguration);
        } catch (TLSAuthException e) {
            logger.atWarn("configure-greengrass-mutual-auth")
                    .log("Error during configure greengrass client mutual auth", e);
        }
        return httpClient;
    }

    private void configureClientMutualTLS(ApacheHttpClient.Builder httpBuilder, DeviceConfiguration deviceConfiguration)
            throws TLSAuthException {
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
            List<X509Certificate> trustCertificates = EncryptionUtils.loadX509Certificates(Paths.get(rootCAPath));

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

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.PreserveStackTrace"})
    KeyManager[] createKeyManagers(String privateKeyPath, String certificatePath) throws TLSAuthException {
        try {
            return RetryUtils.runWithRetry(SECURITY_SERVICE_RETRY_CONFIG, () -> securityService
                            .getKeyManagers(compatibleUriString(privateKeyPath), compatibleUriString(certificatePath)),
                    "get-key-managers", logger);
        } catch (InterruptedException e) {
            logger.atError().setCause(e).kv("privateKeyPath", privateKeyPath).kv("certificatePath", certificatePath)
                    .log("Got interrupted during getting key managers for TLS handshake");
            throw new TLSAuthException("Get key managers interrupted");
        } catch (Exception e) {
            logger.atError().setCause(e).kv("privateKeyPath", privateKeyPath).kv("certificatePath", certificatePath)
                    .log("Error during getting key managers for TLS handshake");
            throw new TLSAuthException("Error during getting key managers", e);
        }
    }

    private String compatibleUriString(String path) {
        try {
            URI u = new URI(path);
            if (Utils.isEmpty(u.getScheme())) {
                // for backward compatibility, if it's a path without scheme, treat it as file path
                u = new URI("file", path, null);
            }
            return u.toString();
        } catch (URISyntaxException e) {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                logger.atDebug()
                        .setCause(e)
                        .kv("path", path)
                        .log("can't parse path string as URI and no file exists at the path");
            }
            // if can't parse the path string as URI, try it as Path and use URI default provider "file"
            return p.toUri().toString();
        }
    }
}
