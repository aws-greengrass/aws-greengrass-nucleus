/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.IOException;

public abstract class NetworkUtils {
    protected final String[] MQTT_PORTS = {"8883", "443"};
    protected final String[] NETWORK_PORTS = {"8443", "443"};
    protected static final Logger logger = LogManager.getLogger(NetworkUtils.class);

    private enum Platform {
        UNKNOWN, LINUX, MACOS
    }
    private static final Platform platform = initialize();

    private static Platform initialize() {
        Platform platform = Platform.UNKNOWN;
        if (PlatformResolver.RANKS.get().containsKey("linux")) {
            platform = Platform.LINUX;
        } else if (PlatformResolver.RANKS.get().containsKey("macos")) {
            platform = Platform.MACOS;
        }
        return platform;
    }

    public static NetworkUtils getByPlatform() {
        switch(platform) {
            case LINUX:
                return new NetworkUtilsLinux();
            case MACOS:
                return new NetworkUtilsMac();
            default:
                throw new UnsupportedOperationException("Platform not supported");
        }
    }

    public abstract void disconnectMqtt() throws InterruptedException, IOException;

    public abstract void recoverMqtt() throws InterruptedException, IOException;

    public abstract void disconnectNetwork() throws InterruptedException, IOException;

    public abstract void recoverNetwork() throws InterruptedException, IOException;
}
