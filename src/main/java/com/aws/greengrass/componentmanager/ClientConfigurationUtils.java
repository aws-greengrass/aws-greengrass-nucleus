/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.crypto.CryptoProvider;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.RegionUtils;
import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

public final class ClientConfigurationUtils {

    private static final Logger logger = LogManager.getLogger(ClientConfigurationUtils.class);

    private ClientConfigurationUtils() {
    }

    /**
     * Get the greengrass service endpoint.
     *
     * @param deviceConfiguration    {@link DeviceConfiguration}
     * @return service end point
     */
    public static String getGreengrassServiceEndpoint(DeviceConfiguration deviceConfiguration) {
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
     * @param deviceConfiguration    {@link DeviceConfiguration}
     * @return configured http client
     */
    public static ApacheHttpClient.Builder getConfiguredClientBuilder(DeviceConfiguration deviceConfiguration) {
        ApacheHttpClient.Builder httpClient = ProxyUtils.getSdkHttpClientBuilder();
        httpClient = httpClient == null ? ApacheHttpClient.builder() : httpClient;

        try {
            configureClientMutualTLS(httpClient, deviceConfiguration.getCryptoProvider());
        } catch (TLSAuthException e) {
            logger.atWarn("configure-greengrass-mutual-auth")
                    .log("Error during configure greengrass client mutual auth", e);
        }
        return httpClient;
    }

    private static void configureClientMutualTLS(ApacheHttpClient.Builder httpBuilder,
                                                 CryptoProvider provider) throws TLSAuthException {
        TrustManager[] trustManagers = provider.get().getTrustManagers();
        KeyManager[] keyManagers = provider.get().getKeyManagers();

        httpBuilder.tlsKeyManagersProvider(() -> keyManagers).tlsTrustManagersProvider(() -> trustManagers);
    }
}
