/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazonaws.SdkClientException;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.Validator;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;

/**
 * Class for providing device configuration information.
 */
@SuppressWarnings("PMD.DataClass")
@SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
public class DeviceConfiguration {

    public static final String DEVICE_PARAM_THING_NAME = "thingName";
    public static final String DEVICE_PARAM_IOT_DATA_ENDPOINT = "iotDataEndpoint";
    public static final String DEVICE_PARAM_IOT_CRED_ENDPOINT = "iotCredEndpoint";
    public static final String DEVICE_PARAM_PRIVATE_KEY_PATH = "privateKeyPath";
    public static final String DEVICE_PARAM_CERTIFICATE_FILE_PATH = "certificateFilePath";
    public static final String DEVICE_PARAM_ROOT_CA_PATH = "rootCaPath";
    public static final String SYSTEM_NAMESPACE_KEY = "system";
    public static final String DEVICE_PARAM_AWS_REGION = "awsRegion";
    public static final String DEVICE_MQTT_NAMESPACE = "mqtt";

    private static final String CANNOT_BE_EMPTY = " cannot be empty";
    private static final Logger logger = LogManager.getLogger(DeviceConfiguration.class);
    private static final String FALLBACK_DEFAULT_REGION = "us-east-1";

    private final Kernel kernel;

    private final Validator deTildeValidator;
    private final Validator regionValidator;

    /**
     * Constructor used to read device cinfiguration from the config store.
     *
     * @param kernel Kernel to get config from
     */
    @Inject
    public DeviceConfiguration(Kernel kernel) {
        this.kernel = kernel;
        deTildeValidator = getDeTildeValidator(kernel);
        regionValidator = getRegionValidator();
    }

    /**
     * Constructor to use when setting the device configuration to kernel config.
     *
     * @param kernel              kernel to set config for
     * @param thingName           IoT thing name
     * @param iotDataEndpoint     IoT data endpoint
     * @param iotCredEndpoint     IoT cert endpoint
     * @param privateKeyPath      private key location on device
     * @param certificateFilePath certificate location on device
     * @param rootCaFilePath      downloaded RootCA location on device
     * @param awsRegion           aws region for the device
     * @throws DeviceConfigurationException when the configuration parameters are not valid
     */
    public DeviceConfiguration(Kernel kernel, String thingName, String iotDataEndpoint, String iotCredEndpoint,
                               String privateKeyPath, String certificateFilePath, String rootCaFilePath,
                               String awsRegion) throws DeviceConfigurationException {
        this.kernel = kernel;
        deTildeValidator = getDeTildeValidator(kernel);
        regionValidator = getRegionValidator();
        getThingName().withValue(thingName);
        getIotDataEndpoint().withValue(iotDataEndpoint);
        getIotCredentialEndpoint().withValue(iotCredEndpoint);
        getPrivateKeyFilePath().withValue(privateKeyPath);
        getCertificateFilePath().withValue(certificateFilePath);
        getRootCAFilePath().withValue(rootCaFilePath);
        getAWSRegion().withValue(awsRegion);

        validate();
    }

    private Validator getDeTildeValidator(Kernel kernel) {
        return (newV, old) -> kernel.deTilde(Coerce.toString(newV));
    }

    @SuppressWarnings("PMD.NullAssignment")
    private Validator getRegionValidator() {
        return (newV, old) -> {
            String region = null;
            if (newV == null || newV instanceof String) {
                region = (String) newV;
            }

            // If the region value is empty/null, then try to get the region from the SDK lookup path
            if (!(newV instanceof String) || Utils.isEmpty(region)) {
                try {
                    region = new DefaultAwsRegionProviderChain().getRegion();
                } catch (SdkClientException ex) {
                    region = null;
                    logger.atWarn().log("Error looking up AWS region", ex);
                }
            }
            if (Utils.isEmpty(region)) {
                logger.atWarn().log("No AWS region found, falling back to default: {}", FALLBACK_DEFAULT_REGION);
                region = FALLBACK_DEFAULT_REGION;
            }

            kernel.getConfig().lookup(SETENV_CONFIG_NAMESPACE, "AWS_DEFAULT_REGION").withValue(region);
            kernel.getConfig().lookup(SETENV_CONFIG_NAMESPACE, "AWS_REGION").withValue(region);

            return region;
        };
    }

    public Topic getThingName() {
        return getTopic(DEVICE_PARAM_THING_NAME);
    }

    public Topic getCertificateFilePath() {
        return getTopic(DEVICE_PARAM_CERTIFICATE_FILE_PATH).addValidator(deTildeValidator);
    }

    public Topic getPrivateKeyFilePath() {
        return getTopic(DEVICE_PARAM_PRIVATE_KEY_PATH).addValidator(deTildeValidator);
    }

    public Topic getRootCAFilePath() {
        return getTopic(DEVICE_PARAM_ROOT_CA_PATH).addValidator(deTildeValidator);
    }

    public Topic getIotDataEndpoint() {
        return getTopic(DEVICE_PARAM_IOT_DATA_ENDPOINT);
    }

    public Topic getIotCredentialEndpoint() {
        return getTopic(DEVICE_PARAM_IOT_CRED_ENDPOINT);
    }

    public Topic getAWSRegion() {
        return getTopic(DEVICE_PARAM_AWS_REGION).addValidator(regionValidator);
    }

    public Topics getMQTTNamespace() {
        return getTopics(DEVICE_MQTT_NAMESPACE);
    }

    public void onAnyChange(ChildChanged cc) {
        kernel.getConfig().lookupTopics(SYSTEM_NAMESPACE_KEY).subscribe(cc);
    }

    /**
     * Validates the device configuration parameters.
     * @throws DeviceConfigurationException when configuration parameters are not valid
     */
    public void validate() throws DeviceConfigurationException {
        String thingName = Coerce.toString(getThingName());
        String certificateFilePath = Coerce.toString(getCertificateFilePath());
        String privateKeyPath = Coerce.toString(getPrivateKeyFilePath());
        String rootCAPath = Coerce.toString(getRootCAFilePath());
        String iotDataEndpoint = Coerce.toString(getIotDataEndpoint());
        String iotCredEndpoint = Coerce.toString(getIotCredentialEndpoint());
        String awsRegion = Coerce.toString(getAWSRegion());
        validateDeviceConfiguration(thingName, certificateFilePath, privateKeyPath, rootCAPath, iotDataEndpoint,
                iotCredEndpoint, awsRegion);
    }

    private Topic getTopic(String parameterName) {
        return kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, parameterName).dflt("");
    }

    private Topics getTopics(String parameterName) {
        return kernel.getConfig().lookupTopics(SYSTEM_NAMESPACE_KEY, parameterName);
    }

    private void validateDeviceConfiguration(String thingName, String certificateFilePath, String privateKeyPath,
                                             String rootCAPath, String iotDataEndpoint, String iotCredEndpoint,
                                             String awsRegion) throws DeviceConfigurationException {
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
        if (Utils.isEmpty(awsRegion)) {
            errors.add(DEVICE_PARAM_AWS_REGION + CANNOT_BE_EMPTY);
        }
        if (!errors.isEmpty()) {
            throw new DeviceConfigurationException(errors.toString());
        }
    }
}
