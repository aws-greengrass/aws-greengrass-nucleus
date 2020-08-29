/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util.orchestration;

import com.aws.iot.evergreen.kernel.KernelAlternatives;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Exec;

import java.io.IOException;
import java.nio.file.Path;

public class SystemdUtils implements SystemServiceUtils {
    protected static final Logger logger = LogManager.getLogger(SystemdUtils.class);
    private static final String PID_FILE_PARAM = "REPLACE_WITH_GG_LOADER_PID_FILE";
    private static final String LOADER_FILE_PARAM = "REPLACE_WITH_GG_LOADER_FILE";
    private static final String SERVICE_CONFIG_FILE_PATH = "/etc/systemd/system/greengrass.service";
    private static final String SED_CMD = "sed -i -e \"s#%s#%s#g\" %s";

    @Override
    public boolean setupSystemService(KernelAlternatives kernelAlternatives) {
        try {
            kernelAlternatives.setupInitLaunchDirIfAbsent();
            Path serviceConfig = kernelAlternatives.getServiceTemplatePath();
            runCommand(String.format(SED_CMD, PID_FILE_PARAM, kernelAlternatives.getLoaderPidPath(), serviceConfig));
            runCommand(String.format(SED_CMD, LOADER_FILE_PARAM, kernelAlternatives.getLoaderPath(), serviceConfig));
            runCommand(String.format("cp %s %s", serviceConfig, SERVICE_CONFIG_FILE_PATH));
            runCommand("systemctl daemon-reload");
            runCommand("systemctl unmask greengrass.service");
            runCommand("systemctl start greengrass.service");
            runCommand("systemctl enable greengrass.service");
            logger.atInfo().log("Successfully set up systemd service");
            return true;
        } catch (IOException ioe) {
            logger.atError().log("Failed to set up systemd service", ioe);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private void runCommand(String command) throws IOException, InterruptedException {
        boolean success = new Exec().withShell(command)
                .withOut(s -> logger.atInfo().kv("command", command).kv("stdout", s.toString().trim()).log())
                .withErr(s -> logger.atWarn().kv("command", command).kv("stderr", s.toString().trim()).log())
                .successful(false);
        if (!success) {
            throw new IOException(String.format("Command %s failed", command));
        }
    }
}
