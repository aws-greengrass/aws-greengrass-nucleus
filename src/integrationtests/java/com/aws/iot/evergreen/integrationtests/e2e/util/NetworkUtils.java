/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.util.Arrays;
import java.util.List;

public abstract class NetworkUtils {
    protected final List<String> MQTT_PORTS = Arrays.asList("8883", "443");
    protected static final Logger logger = LogManager.getLogger(NetworkUtils.class);

    private enum Platform {
        UNKNOWN, LINUX, MACOS
    }
    private static final Platform cmd = initialize();

    private static Platform initialize() {
        Platform cmd = Platform.UNKNOWN;
        if (PlatformResolver.RANKS.containsKey("linux")) {
            cmd = Platform.LINUX;
        } else if (PlatformResolver.RANKS.containsKey("macos")) {
            cmd = Platform.MACOS;
        }
        return cmd;
    }

    public static NetworkUtils getByPlatform() {
        switch(cmd) {
            case LINUX:
                return new NetworkUtilsLinux();
            case MACOS:
                return new NetworkUtilsMac();
            default:
                throw new UnsupportedOperationException("Platform not supported");
        }
    }

    public abstract void disconnect() throws Exception;

    public abstract void recover() throws Exception;
}
