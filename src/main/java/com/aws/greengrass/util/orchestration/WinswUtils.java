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
import javax.inject.Inject;

public class WinswUtils implements SystemServiceUtils {
    protected static final Logger logger = LogManager.getLogger(WinswUtils.class);
    private static final String LOADER_FILE_PARAM = "REPLACE_WITH_GG_LOADER_FILE";
    private static final String ROOT_DIR_PARAM = "REPLACE_WITH_GG_ROOT_DIR";
    private static final String LOG_EVENT_NAME = "winsw-setup";
    private static final String WINSW_SERVICE_FILE = "greengrass.xml";
    private static final String WINSW_SERVICE_TEMPLATE = "greengrass.xml.template";
    private final NucleusPaths nucleusPaths;

    @Inject
    public WinswUtils(NucleusPaths nucleusPaths) {
        this.nucleusPaths = nucleusPaths;
    }

    @Override
    public boolean setupSystemService(KernelAlternatives kernelAlternatives, boolean start) {
        logger.atDebug(LOG_EVENT_NAME).log("Start Windows service setup");
        try {
            kernelAlternatives.setupInitLaunchDirIfAbsent();

            Path serviceTemplate = kernelAlternatives.getBinDir().resolve(WINSW_SERVICE_TEMPLATE);
            if (!Files.exists(serviceTemplate)) {
                throw new IOException("Missing service template file at: " + serviceTemplate);
            }
            Path loaderPath = kernelAlternatives.getLoaderPath();
            if (!Files.exists(serviceTemplate)) {
                throw new IOException("Missing loader file at: " + loaderPath);
            }

            Path serviceConfig = kernelAlternatives.getBinDir().resolve(WINSW_SERVICE_FILE);
            interpolateServiceTemplate(serviceTemplate, serviceConfig, kernelAlternatives);

            String ggExe = kernelAlternatives.getBinDir().resolve("greengrass.exe").toAbsolutePath().toString();
            // cleanup any previous instance of the service before installing and starting
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME, ggExe + " stop " + serviceConfig, true);
            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME, ggExe + " uninstall " + serviceConfig, true);

            SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME, ggExe + " install " + serviceConfig, false);
            if (start) {
                SystemServiceUtils.runCommand(logger, LOG_EVENT_NAME, ggExe + " start " + serviceConfig, false);
            }

            logger.atInfo(LOG_EVENT_NAME).log("Successfully set up Windows service");
            return true;
        } catch (IOException ioe) {
            logger.atError(LOG_EVENT_NAME).log("Failed to set up Windows service", ioe);
        } catch (InterruptedException e) {
            logger.atError(LOG_EVENT_NAME).log("Interrupted", e);
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private void interpolateServiceTemplate(Path src, Path dst, KernelAlternatives kernelAlternatives)
            throws IOException {
        try (BufferedReader r = Files.newBufferedReader(src); BufferedWriter w = Files.newBufferedWriter(dst)) {
            String line = r.readLine();
            while (line != null) {
                w.write(line.replace(ROOT_DIR_PARAM, nucleusPaths.rootPath().toAbsolutePath().toString())
                        .replace(LOADER_FILE_PARAM, kernelAlternatives.getLoaderPath().toString()));
                w.newLine();
                line = r.readLine();
            }
            w.flush();
        }
    }
}
