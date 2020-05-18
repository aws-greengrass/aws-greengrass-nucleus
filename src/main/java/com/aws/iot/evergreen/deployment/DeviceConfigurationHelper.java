/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.deployment.model.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.KernelCommandLine;
import com.aws.iot.evergreen.util.Utils;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/**
 * Class for providing device configuration information.
 */
public class DeviceConfigurationHelper {

    public static final String DEVICE_PARAM_THING_NAME = "thingName";
    public static final String DEVICE_PARAM_IOT_DATA_ENDPOINT = "iotDataEndpoint";
    public static final String DEVICE_PARAM_IOT_CRED_ENDPOINT = "iotCredEndpoint";
    public static final String DEVICE_PARAM_PRIVATE_KEY_PATH = "privateKeyPath";
    public static final String DEVICE_PARAM_CERTIFICATE_FILE_PATH = "certificateFilePath";
    public static final String DEVICE_PARAM_ROOT_CA_PATH = "rootCaPath";
    private static final String CANNOT_BE_EMPTY = " cannot be empty";

    @Inject
    private Kernel kernel;

    @Inject
    private KernelCommandLine kernelCommandLine;

    /**
     * Retrieves the device configuration information from kernel config to communicate with Iot Cloud.
     *
     * @return {@link DeviceConfiguration}
     * @throws DeviceConfigurationException when configuration is not available for the device.
     */
    public DeviceConfiguration getDeviceConfiguration() throws DeviceConfigurationException {
        String thingName = getStringParameterFromConfig(DEVICE_PARAM_THING_NAME);
        String certificateFilePath =
                kernelCommandLine.deTilde(getStringParameterFromConfig(DEVICE_PARAM_CERTIFICATE_FILE_PATH));
        String privateKeyPath = kernelCommandLine.deTilde(getStringParameterFromConfig(DEVICE_PARAM_PRIVATE_KEY_PATH));
        String rootCAPath = kernelCommandLine.deTilde(getStringParameterFromConfig(DEVICE_PARAM_ROOT_CA_PATH));
        String iotDataEndpoint = getStringParameterFromConfig(DEVICE_PARAM_IOT_DATA_ENDPOINT);
        String iotCredEndpoint = getStringParameterFromConfig(DEVICE_PARAM_IOT_CRED_ENDPOINT);
        validateDeviceConfiguration(thingName, certificateFilePath, privateKeyPath, rootCAPath, iotDataEndpoint,
                iotCredEndpoint);
        return new DeviceConfiguration(thingName, certificateFilePath, privateKeyPath, rootCAPath, iotDataEndpoint,
                iotCredEndpoint);
    }

    private String getStringParameterFromConfig(String parameterName) {
        String paramValue = "";
        //TODO: Update when device provisioning is implemented
        Topic childTopic = kernel.getConfig()
                .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, DeploymentService.DEPLOYMENT_SERVICE_TOPICS)
                .findLeafChild(parameterName);
        if (childTopic != null && childTopic.getOnce() != null) {
            paramValue = childTopic.getOnce().toString();
        }
        return paramValue;
    }

    private void validateDeviceConfiguration(String thingName, String certificateFilePath, String privateKeyPath,
                                             String rootCAPath, String iotDataEndpoint, String iotCredEndpoint)
            throws DeviceConfigurationException {
        List<String> errors = new ArrayList<>();
        if (Utils.isEmpty(thingName)) {
            errors.add(DEVICE_PARAM_THING_NAME + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(certificateFilePath)) {
            errors.add(DEVICE_PARAM_CERTIFICATE_FILE_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(privateKeyPath)) {
            errors.add(DEVICE_PARAM_PRIVATE_KEY_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(rootCAPath)) {
            errors.add(DEVICE_PARAM_ROOT_CA_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(iotDataEndpoint)) {
            errors.add(DEVICE_PARAM_IOT_DATA_ENDPOINT + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(iotCredEndpoint)) {
            errors.add(DEVICE_PARAM_IOT_CRED_ENDPOINT + CANNOT_BE_EMPTY);
        }
        if (!errors.isEmpty()) {
            throw new DeviceConfigurationException(errors.toString());
        }
    }
}
