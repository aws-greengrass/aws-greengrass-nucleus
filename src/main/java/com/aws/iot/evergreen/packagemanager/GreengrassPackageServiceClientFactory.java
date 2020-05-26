/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.greengrasspackagemanagement.AWSGreengrassPackageManagement;
import com.amazonaws.services.greengrasspackagemanagement.AWSGreengrassPackageManagementClientBuilder;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Utils;
import lombok.Getter;

import javax.inject.Inject;
import javax.inject.Named;

@Getter
public class GreengrassPackageServiceClientFactory {

    private static final Logger logger = LogManager.getLogger(GreengrassPackageServiceClientFactory.class);

    private final AWSGreengrassPackageManagement pmsClient;

    /**
     * Constructor with custom endpoint/region configuration.
     *
     * @param greengrassServiceEndpoint String containing service endpoint
     * @param deviceConfiguration Device configuration
     */
    @Inject
    public GreengrassPackageServiceClientFactory(
            @Named("greengrassServiceEndpoint") String greengrassServiceEndpoint,
            DeviceConfiguration deviceConfiguration) {

        String region = Coerce.toString(deviceConfiguration.getAWSRegion());
        if (Utils.isEmpty(greengrassServiceEndpoint)) {
            // Initialize default client, client builder determines endpoint configuration
            // Will try to use default credential provider and environment region configuration
            logger.atInfo("initialize-pms-client")
                  .addKeyValue("service-endpoint", "default")
                  .addKeyValue("service-region", region)
                  .log();
            this.pmsClient = AWSGreengrassPackageManagementClientBuilder.standard().withRegion(region).build();
        } else {
            AWSGreengrassPackageManagementClientBuilder clientBuilder
                    = AWSGreengrassPackageManagementClientBuilder.standard();
            logger.atInfo("initialize-pms-client")
                  .addKeyValue("service-endpoint", greengrassServiceEndpoint)
                  .addKeyValue("service-region", region)
                  .log();
            clientBuilder.withEndpointConfiguration(
                    new AwsClientBuilder.EndpointConfiguration(greengrassServiceEndpoint, region));
            this.pmsClient = clientBuilder.build();
        }
        // TODO: Might need to retrieve AWS credentials from custom credential provider
    }
}
