/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.amazonaws.SdkClientException;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Validator;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

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
    private static final String CANNOT_BE_EMPTY = " cannot be empty";
    private static final String DEVICE_PARAM_AWS_REGION = "awsRegion";
    private static final Logger logger = LogManager.getLogger(DeviceConfiguration.class);
    private static final String FALLBACK_DEFAULT_REGION = "us-east-1";

    private final Kernel kernel;

    private final Validator deTildeValidator;
    private final Validator regionValidator;

    /**
     * Constructor.
     *
     * @param kernel Kernel to get config from
     */
    @Inject
    @SuppressWarnings("PMD.NullAssignment")
    public DeviceConfiguration(Kernel kernel) {
        this.kernel = kernel;
        deTildeValidator = (newV, old) -> kernel.deTilde(Coerce.toString(newV));
        regionValidator = (newV, old) -> {
            // If the region value is empty/null, then try to get the region from the SDK lookup path
            if (!(newV instanceof String) || Utils.isEmpty((String) newV)) {
                try {
                    newV = new DefaultAwsRegionProviderChain().getRegion();
                } catch (SdkClientException ex) {
                    newV = null;
                    logger.atWarn().log("Error looking up AWS region", ex);
                }
            }
            if (Utils.isEmpty((String) newV)) {
                logger.atWarn().log("No AWS region found, falling back to default: {}", FALLBACK_DEFAULT_REGION);
                newV = FALLBACK_DEFAULT_REGION;
            }
            return newV;
        };
        validate();
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

    private void validate() {
        String thingName = Coerce.toString(getThingName());
        String certificateFilePath = Coerce.toString(getCertificateFilePath());
        String privateKeyPath = Coerce.toString(getPrivateKeyFilePath());
        String rootCAPath = Coerce.toString(getRootCAFilePath());
        String iotDataEndpoint = Coerce.toString(getIotDataEndpoint());
        String iotCredEndpoint = Coerce.toString(getIotCredentialEndpoint());
        String awsRegion = Coerce.toString(getAWSRegion());
        try {
            validateDeviceConfiguration(thingName, certificateFilePath, privateKeyPath, rootCAPath, iotDataEndpoint,
                    iotCredEndpoint, awsRegion);
        } catch (DeviceConfigurationException e) {
            logger.atError().setCause(e).log();
        }
    }

    private Topic getTopic(String parameterName) {
        return kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, parameterName).dflt("");
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
