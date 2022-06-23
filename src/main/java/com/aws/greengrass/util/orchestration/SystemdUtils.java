/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.orchestration;

import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class SystemdUtils implements SystemServiceUtils {
    protected static final Logger logger = LogManager.getLogger(SystemdUtils.class);
    private static final String PID_FILE_PARAM = "REPLACE_WITH_GG_LOADER_PID_FILE";
    private static final String LOADER_FILE_PARAM = "REPLACE_WITH_GG_LOADER_FILE";
    private static final String SERVICE_CONFIG_FILE_PATH = "/etc/systemd/system/greengrass.service";
    private static final String LOG_EVENT_NAME = "systemd-setup";
    private static final String SYSTEMD_SERVICE_FILE = "greengrass.service";
    private static final String SYSTEMD_SERVICE_TEMPLATE = "greengrass.service.template";

    @Override
    public boolean setupSystemService(KernelAlternatives kernelAlternatives, boolean start) {
        logger.atDebug(LOG_EVENT_NAME).log("Start systemd setup");
        try {
            kernelAlternatives.setupInitLaunchDirIfAbsent();

            Path serviceTemplate = kernelAlternatives.getBinDir().resolve(SYSTEMD_SERVICE_TEMPLATE);
            if (!Files.exists(serviceTemplate)) {
                throw new IOException("Missing service template file at: " + serviceTemplate);
            }
            Path loaderPath = kernelAlternatives.getLoaderPath();
            if (!Files.exists(serviceTemplate)) {
                throw new IOException("Missing loader file at: " + loaderPath);
            }

            Path serviceConfig = kernelAlternatives.getBinDir().resolve(SYSTEMD_SERVICE_FILE);
            interpolateServiceTemplate(serviceTemplate, serviceConfig, kernelAlternatives);

            Files.copy(serviceConfig, Paths.get(SERVICE_CONFIG_FILE_PATH), REPLACE_EXISTING);
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,"systemctl daemon-reload", false);
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,"systemctl unmask greengrass.service", false);
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,"systemctl stop greengrass.service", false);
            if (start) {
                SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,"systemctl start greengrass.service", false);
            }
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,"systemctl enable greengrass.service", false);

            logger.atInfo(LOG_EVENT_NAME).log("Successfully set up systemd service");
            return true;
        } catch (IOException ioe) {
            logger.atError(LOG_EVENT_NAME).log("Failed to set up systemd service", ioe);
        } catch (InterruptedException e) {
            logger.atError(LOG_EVENT_NAME).log("Interrupted", e);
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private void interpolateServiceTemplate(Path src, Path dst, KernelAlternatives kernelAlternatives)
            throws IOException {
        try (BufferedReader r = Files.newBufferedReader(src);
             BufferedWriter w = Files.newBufferedWriter(dst)) {
            String line = r.readLine();
            while (line != null) {
                w.write(line.replace(PID_FILE_PARAM, kernelAlternatives.getLoaderPidPath().toString())
                        .replace(LOADER_FILE_PARAM, kernelAlternatives.getLoaderPath().toString()));
                w.newLine();
                line = r.readLine();
            }
            w.flush();
        }
    }
}
