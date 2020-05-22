/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.greengrasscomponentmanagement.AWSGreengrassComponentManagement;
import com.amazonaws.services.greengrasscomponentmanagement.AWSGreengrassComponentManagementClientBuilder;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Utils;
import lombok.Getter;

import javax.inject.Inject;
import javax.inject.Named;

@Getter
@SuppressWarnings("PMD.ConfusingTernary")
public class GreengrassPackageServiceClientFactory {

    private static final Logger logger = LogManager.getLogger(GreengrassPackageServiceClientFactory.class);

    private final AWSGreengrassComponentManagement cmsClient;

    /**
     * Constructor with custom endpoint/region configuration.
     *
     * @param greengrassServiceEndpoint String containing service endpoint
     * @param deviceConfiguration       Device configuration
     * @param credentialsProvider       AWS Credentials provider for device credentials
     */
    @Inject
    public GreengrassPackageServiceClientFactory(@Named("greengrassServiceEndpoint") String greengrassServiceEndpoint,
             DeviceConfiguration deviceConfiguration,
             @Named("greengrassServiceCredentialProvider") AWSCredentialsProvider credentialsProvider) {
        AWSGreengrassComponentManagementClientBuilder clientBuilder =
                AWSGreengrassComponentManagementClientBuilder.standard();
        String region = Coerce.toString(deviceConfiguration.getAWSRegion());

        if (!Utils.isEmpty(region)) {
            if (!Utils.isEmpty(greengrassServiceEndpoint)) {
                // Region and endpoint are both required when updating endpoint config
                logger.atInfo("initialize-cms-client").addKeyValue("service-endpoint", greengrassServiceEndpoint)
                        .addKeyValue("service-region", region).log();
                EndpointConfiguration endpointConfiguration =
                        new EndpointConfiguration(greengrassServiceEndpoint, region);
                clientBuilder.withEndpointConfiguration(endpointConfiguration);
            } else {
                // This section is to override default region if needed
                logger.atInfo("initialize-cms-client").addKeyValue("service-region", region).log();
                clientBuilder.withRegion(region);
            }
        }

        if (credentialsProvider != null) {
            clientBuilder.withCredentials(credentialsProvider);
        }

        this.cmsClient = clientBuilder.build();
    }
}
