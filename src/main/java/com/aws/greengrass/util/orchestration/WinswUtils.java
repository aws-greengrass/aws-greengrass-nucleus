/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.orchestration;

import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Platform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
            String serviceConfigStr = serviceConfig.toString();

            String ggExe = kernelAlternatives.getBinDir().resolve("greengrass.exe").toAbsolutePath().toString();
            // cleanup any previous instance of the service before installing and starting
            runCommand(true, ggExe, "stop", serviceConfigStr);
            runCommand(true, ggExe, "uninstall", serviceConfigStr);

            runCommand(false, ggExe, "install", serviceConfigStr);
            if (start) {
                runCommand(false, ggExe, "start", serviceConfigStr);
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

    @SuppressWarnings("PMD.CloseResource")
    void runCommand(boolean ignoreError, String... command)
            throws IOException, InterruptedException {
        logger.atDebug(LOG_EVENT_NAME).log("{}", (Object) command);
        Exec exec = Platform.getInstance().createNewProcessRunner().withExec(command);
        if (Platform.getInstance().getPrivilegedUser() != null) {
            exec.withUser(Platform.getInstance().getPrivilegedUser());
        }
        if (Platform.getInstance().getPrivilegedGroup() != null) {
            exec.withGroup(Platform.getInstance().getPrivilegedGroup());
        }
        String commandStr = Arrays.toString(command);
        boolean success = exec
                .withOut(s -> logger.atWarn(LOG_EVENT_NAME).kv("command", commandStr)
                        .kv("stdout", s.toString().trim()).log())
                .withErr(s -> logger.atError(LOG_EVENT_NAME).kv("command", commandStr)
                        .kv("stderr", s.toString().trim()).log())
                .successful(true);
        if (!success && !ignoreError) {
            throw new IOException(String.format("Command %s failed", commandStr));
        }
    }
}
