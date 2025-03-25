/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

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
import org.apache.http.client.utils.URIBuilder;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;

public final class ClientConfigurationUtils {
    private static final Logger logger = LogManager.getLogger(ClientConfigurationUtils.class);

    // Utility class. These methods are used by plugins so this interface *must not* change
    private ClientConfigurationUtils() {
    }

    /**
     * Get the greengrass service endpoint URI.
     *
     * @param deviceConfiguration {@link DeviceConfiguration}
     * @return service endpoint URI
     */
    @SuppressWarnings("PMD.UseStringBufferForStringAppends")
    public static String getGreengrassServiceEndpoint(DeviceConfiguration deviceConfiguration) {
        IotSdkClientFactory.EnvironmentStage stage;
        try {
            stage = IotSdkClientFactory.EnvironmentStage
                    .fromString(Coerce.toString(deviceConfiguration.getEnvironmentStage()));
        } catch (InvalidEnvironmentStageException e) {
            logger.atError().setCause(e).log("Caught exception while parsing Nucleus args");
            throw new RuntimeException(e);
        }

        // Use customer configured GG endpoint if it is set
        String endpoint = Coerce.toString(deviceConfiguration.getGGDataEndpoint());
        int port = Coerce.toInt(deviceConfiguration.getGreengrassDataPlanePort());
        if (Utils.isEmpty(endpoint)) {
            // Fallback to global endpoint if no GG endpoint was specified
            return RegionUtils.getGreengrassDataPlaneEndpoint(Coerce.toString(deviceConfiguration.getAWSRegion()),
                    stage, port);
        }

        // If customer specifies "iotdata" then use the iotdata endpoint, rather than needing them to specify the same
        // endpoint twice in the config.
        String iotData = Coerce.toString(deviceConfiguration.getIotDataEndpoint());
        if ("iotdata".equalsIgnoreCase(endpoint) && Utils.isNotEmpty(iotData)) {
            // Use customer configured IoT data endpoint if it is set
            endpoint = iotData;
        }

        // This method returns a URI, not just an endpoint
        if (!endpoint.startsWith("https://")) {
            endpoint = "https://" + endpoint;
        }
        try {
            URI endpointUri = new URI(endpoint);
            // If the port is defined in the URI, then return it as-is
            if (endpointUri.getPort() != -1) {
                return endpoint;
            }
            // Modify the URI with the user's chosen port
            return new URIBuilder(endpointUri).setPort(port).toString();
        } catch (URISyntaxException e) {
            logger.atError().log("Invalid endpoint {}", endpoint, e);
            return RegionUtils.getGreengrassDataPlaneEndpoint(Coerce.toString(deviceConfiguration.getAWSRegion()),
                    stage, port);
        }
    }

    /**
     * Configure the http client builder with the required certificates for the mutual auth connection.
     *
     * @param deviceConfiguration {@link DeviceConfiguration}
     * @return configured http client
     * @throws TLSAuthException if there is an issue configuring the http client
     */
    public static ApacheHttpClient.Builder getConfiguredClientBuilder(DeviceConfiguration deviceConfiguration)
            throws TLSAuthException {
        ApacheHttpClient.Builder httpClient = ProxyUtils.getSdkHttpClientBuilder();
        configureClientMutualTLS(httpClient, deviceConfiguration);
        return httpClient;
    }

    private static void configureClientMutualTLS(ApacheHttpClient.Builder httpBuilder,
                                           DeviceConfiguration deviceConfiguration)
            throws TLSAuthException {
        String rootCAPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        if (Utils.isEmpty(rootCAPath)) {
            return;
        }

        TrustManager[] trustManagers = createTrustManagers(rootCAPath);
        KeyManager[] keyManagers = deviceConfiguration.getDeviceIdentityKeyManagers();

        httpBuilder.tlsKeyManagersProvider(() -> keyManagers).tlsTrustManagersProvider(() -> trustManagers);
    }

    private static TrustManager[] createTrustManagers(String rootCAPath) throws TLSAuthException {
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
}
