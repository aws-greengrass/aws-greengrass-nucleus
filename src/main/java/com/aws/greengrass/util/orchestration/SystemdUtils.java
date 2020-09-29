/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.orchestration;

import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SystemdUtils implements SystemServiceUtils {
    protected static final Logger logger = LogManager.getLogger(SystemdUtils.class);
    private static final String PID_FILE_PARAM = "REPLACE_WITH_GG_LOADER_PID_FILE";
    private static final String LOADER_FILE_PARAM = "REPLACE_WITH_GG_LOADER_FILE";
    private static final String SERVICE_CONFIG_FILE_PATH = "/etc/systemd/system/greengrass.service";

    @Override
    public boolean setupSystemService(KernelAlternatives kernelAlternatives) {
        try {
            kernelAlternatives.setupInitLaunchDirIfAbsent();
            Path serviceConfig = kernelAlternatives.getServiceConfigPath();
            interpolateServiceTemplate(kernelAlternatives.getServiceTemplatePath(), serviceConfig, kernelAlternatives);

            runCommand(String.format("sudo cp %s %s", serviceConfig, SERVICE_CONFIG_FILE_PATH));
            runCommand("sudo systemctl daemon-reload");
            runCommand("sudo systemctl unmask greengrass.service");
            runCommand("sudo systemctl start greengrass.service");
            runCommand("sudo systemctl enable greengrass.service");

            logger.atInfo().log("Successfully set up systemd service");
            return true;
        } catch (IOException ioe) {
            logger.atError().log("Failed to set up systemd service", ioe);
        } catch (InterruptedException e) {
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

    private void runCommand(String command) throws IOException, InterruptedException {
        boolean success = new Exec().withShell(command)
                .withOut(s -> logger.atWarn().kv("command", command).kv("stdout", s.trim()).log())
                .withErr(s -> logger.atError().kv("command", command).kv("stderr", s.trim()).log())
                .successful(true);
        if (!success) {
            throw new IOException(String.format("Command %s failed", command));
        }
    }
}
