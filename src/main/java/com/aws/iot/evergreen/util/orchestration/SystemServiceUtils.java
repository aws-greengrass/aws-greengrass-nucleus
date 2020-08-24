/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util.orchestration;

import com.aws.iot.evergreen.kernel.KernelAlternatives;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public abstract class SystemServiceUtils {
    protected static final Logger logger = LogManager.getLogger(SystemServiceUtils.class);
    private static SystemServiceUtils INSTANCE;

    /**
     * Get the appropriate instance of Platform for the current platform.
     *
     * @return Platform
     */
    public static synchronized SystemServiceUtils getInstance() {
        if (INSTANCE != null) {
            return INSTANCE;
        }

        if (SystemServiceManager.SYSTEMD.equals(getSystemServiceManager())) {
            INSTANCE = new SystemdUtils();
        } else {
            INSTANCE = new InitUtils();
        }

        return INSTANCE;
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    protected static SystemServiceManager getSystemServiceManager() {

        try {
            String bootPath = Files.readSymbolicLink(Paths.get("/sbin/init")).toString();
            if (bootPath.contains("systemd")) {
                return SystemServiceManager.SYSTEMD;
            }
        } catch (IOException e) {
            logger.atError().log("Unable to determine init process type");
        }
        return SystemServiceManager.INIT;
    }

    /**
     * Setup Greengrass as a system service.
     *
     * @param kernelAlternatives KernelAlternatives instance which manages launch directory
     * @return true if setup is successful, false otherwise
     */
    public abstract boolean setupSystemService(KernelAlternatives kernelAlternatives);

    enum SystemServiceManager {
        SYSTEMD, INIT
    }
}
