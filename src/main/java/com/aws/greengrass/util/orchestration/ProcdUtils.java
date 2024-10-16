/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.orchestration;

import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.NucleusPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class ProcdUtils implements SystemServiceUtils {
    protected static final Logger logger = LogManager.getLogger(ProcdUtils.class);
    private static final String PID_FILE_PARAM = "REPLACE_WITH_GG_LOADER_PID_FILE";
    private static final String LOADER_FILE_PARAM = "REPLACE_WITH_GG_LOADER_FILE";
    private static final String JAVA_HOME_PARAM = "REPLACE_WITH_GG_JAVA_HOME";
    private static final String SERVICE_CONFIG_FILE_PATH = "/etc/init.d/greengrass.service";
    private static final String LOG_EVENT_NAME = "procd-setup";
    private static final String PROCD_SERVICE_FILE = "greengrass.service";
    private static final String PROCD_SERVICE_TEMPLATE = "greengrass.service.procd.template";

    @Override
    public boolean setupSystemService(KernelAlternatives kernelAlternatives, NucleusPaths nucleusPaths, boolean start) {
        logger.atInfo(LOG_EVENT_NAME).log("Start procd setup");
        try {
            kernelAlternatives.setupInitLaunchDirIfAbsent();

            Path serviceTemplate = kernelAlternatives.getBinDir().resolve(PROCD_SERVICE_TEMPLATE);
            if (!Files.exists(serviceTemplate)) {
                throw new IOException("Missing service template file at: " + serviceTemplate);
            }
            Path loaderPath = kernelAlternatives.getLoaderPath();
            if (!Files.exists(serviceTemplate)) {
                throw new IOException("Missing loader file at: " + loaderPath);
            }

            Path serviceConfig = kernelAlternatives.getBinDir().resolve(PROCD_SERVICE_FILE);
            interpolateServiceTemplate(serviceTemplate, serviceConfig, kernelAlternatives);

            Files.copy(serviceConfig, Paths.get(SERVICE_CONFIG_FILE_PATH), REPLACE_EXISTING);
            Files.setPosixFilePermissions(Paths.get(SERVICE_CONFIG_FILE_PATH),
                    PosixFilePermissions.fromString("rwxr-xr-x"));

            // The "service" command of procd is a function instead of an executable daemon, and it's not default
            // configured in "/bin/sh". So we launch this service through System V style.
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,SERVICE_CONFIG_FILE_PATH + " reload", false);
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,SERVICE_CONFIG_FILE_PATH + " stop", false);
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,SERVICE_CONFIG_FILE_PATH + " enable", false);
            if (start) {
                SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME,SERVICE_CONFIG_FILE_PATH + " start", false);
            }

            logger.atInfo(LOG_EVENT_NAME).log("Successfully set up procd service");
            return true;
        } catch (IOException ioe) {
            logger.atError(LOG_EVENT_NAME).log("Failed to set up procd service", ioe);
        } catch (InterruptedException e) {
            logger.atError(LOG_EVENT_NAME).log("Interrupted", e);
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private void interpolateServiceTemplate(Path src, Path dst, KernelAlternatives kernelAlternatives)
            throws IOException {
        String javaHome = System.getProperty("java.home");
        try (BufferedReader r = Files.newBufferedReader(src);
             BufferedWriter w = Files.newBufferedWriter(dst)) {
            String line = r.readLine();
            while (line != null) {
                w.write(line.replace(PID_FILE_PARAM, kernelAlternatives.getLoaderPidPath().toString())
                        .replace(LOADER_FILE_PARAM, kernelAlternatives.getLoaderPath().toString())
                        .replace(JAVA_HOME_PARAM, javaHome));
                w.newLine();
                line = r.readLine();
            }
            w.flush();
        }
    }
}
