/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util.platforms;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.kernel.KernelAlternatives;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Exec;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public abstract class Platform {
    protected static final Logger logger = LogManager.getLogger(Platform.class);
    private static Platform INSTANCE;

    /**
     * Get the appropriate instance of Platform for the current platform.
     *
     * @return Platform
     */
    public static synchronized Platform getInstance() {
        if (INSTANCE != null) {
            return INSTANCE;
        }

        if (Exec.isWindows) {
            INSTANCE = new WindowsPlatform();
        } else if (PlatformResolver.RANKS.get().containsKey("qnx")) {
            INSTANCE = new QNXPlatform();
        } else if (SystemServiceManager.SYSTEMD.equals(getSystemServiceManager())) {
            INSTANCE = new SystemdUnixPlatform();
        } else {
            INSTANCE = new UnixPlatform();
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

    public abstract void killProcessAndChildren(Process process, boolean force)
            throws IOException, InterruptedException;

    public abstract String[] getShellForCommand(String command);

    public abstract int exitCodeWhenCommandDoesNotExist();

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
