/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.provisioning.resource;

import com.aws.greengrass.provisioning.DeviceIdentityInterface;
import com.aws.greengrass.provisioning.ProvisionConfiguration;
import com.aws.greengrass.provisioning.ProvisionContext;
import com.aws.greengrass.provisioning.exceptions.RetryableProvisioningException;

import java.util.Map;

public class TestDeviceProvisioningPluginForJar implements DeviceIdentityInterface {
    private static final String SERVICE_NAME = "aws.greengrass.TestProvisioningPluginForJar";

    @Override
    public ProvisionConfiguration updateIdentityConfiguration(ProvisionContext provisionContext) throws RetryableProvisioningException {
        Map<String, Object> parameterMap = provisionContext.getParameterMap();
        ProvisionConfiguration.SystemConfiguration systemConfiguration =
                new ProvisionConfiguration.SystemConfiguration();
        systemConfiguration.setCertificateFilePath(parameterMap.get("certificateFilePath") == null ? ""
                : parameterMap.get("certificateFilePath").toString());
        systemConfiguration.setPrivateKeyPath(parameterMap.get("privateKeyPath") == null ? ""
                : parameterMap.get("privateKeyPath").toString());
        systemConfiguration.setRootCAPath(parameterMap.get("rootCAPath") == null ? ""
                : parameterMap.get("rootCAPath").toString());
        systemConfiguration.setThingName(parameterMap.get("thingName") == null ? ""
                : parameterMap.get("thingName").toString());
        ProvisionConfiguration.NucleusConfiguration nucleusConfiguration =
                new ProvisionConfiguration.NucleusConfiguration();
        nucleusConfiguration.setAwsRegion(parameterMap.get("awsRegion") == null ? ""
                : parameterMap.get("awsRegion").toString());
        nucleusConfiguration.setIotCredentialsEndpoint(parameterMap.get("iotCredentialsEndpoint") == null ? ""
                : parameterMap.get("iotCredentialsEndpoint").toString());
        nucleusConfiguration.setIotDataEndpoint(parameterMap.get("iotDataEndpoint") == null ? ""
                : parameterMap.get("iotDataEndpoint").toString());
        nucleusConfiguration.setIotRoleAlias(parameterMap.get("iotRoleAlias") == null ? ""
                : parameterMap.get("iotRoleAlias").toString());

        ProvisionConfiguration provisionConfiguration = new ProvisionConfiguration();
        provisionConfiguration.setSystemConfiguration(systemConfiguration);
        provisionConfiguration.setNucleusConfiguration(nucleusConfiguration);

        try {
            if (parameterMap.get("waitTime") != null) {
                Thread.sleep(Long.valueOf(parameterMap.get("waitTime").toString()));
            }
        } catch (InterruptedException ignored) {
        }
        return provisionConfiguration;
    }

    @Override
    public String name() {
        return SERVICE_NAME;
    }
}
