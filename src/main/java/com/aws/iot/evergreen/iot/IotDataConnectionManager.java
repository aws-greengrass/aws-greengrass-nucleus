package com.aws.iot.evergreen.iot;

import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.util.Coerce;

public class IotDataConnectionManager extends IotConnectionManager {
    /**
     * Constructor.
     *
     * @param deviceConfiguration Device configuration helper getting cert and keys for mTLS
     * @throws DeviceConfigurationException When unable to initialize this manager.
     */
    public IotDataConnectionManager(DeviceConfiguration deviceConfiguration) throws DeviceConfigurationException {
        super(deviceConfiguration, Coerce.toString(deviceConfiguration.getIotDataEndpoint()));
    }
}
