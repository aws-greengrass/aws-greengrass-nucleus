/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.orchestration;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AllArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.locks.Lock;
import javax.inject.Inject;

@AllArgsConstructor(onConstructor = @__(@Inject))
public class SystemServiceUtilsFactory {
    protected static final Logger logger = LogManager.getLogger(SystemServiceUtilsFactory.class);

    private final Context context;
    private final Lock lock = LockFactory.newReentrantLock(this);

    /**
     * Get the appropriate instance of Platform for the current platform.
     *
     * @return Platform
     */
    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    public SystemServiceUtils getInstance() {
        try (LockScope ls = LockScope.lock(lock)) {
            if (PlatformResolver.isWindows) {
                return context.get(WinswUtils.class);
            }

            try {
                String bootPath = Files.readSymbolicLink(Paths.get("/sbin/init")).toString();
                if (bootPath.contains("systemd")) {
                    logger.atDebug().log("Detected systemd on the device");
                    return context.get(SystemdUtils.class);
                }
            } catch (IOException e) {
                logger.atDebug().log("Unable to determine init process type");
            }

            if (new File("/sbin/procd").isFile()) {
                logger.atDebug().log("Detected procd on the device");
                return context.get(ProcdUtils.class);
            } else {
                logger.atDebug().log("No procd is detected");
            }

            logger.atError().log("Unable to determine system service type");
            return context.get(InitUtils.class);
        }
    }
}
