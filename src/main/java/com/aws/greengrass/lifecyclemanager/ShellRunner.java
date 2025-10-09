/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.util.Utils.isEmpty;

public interface ShellRunner {

    Exec setup(String note, String command, GreengrassService onBehalfOf) throws IOException;

    boolean successful(Exec e, String note, IntConsumer background, GreengrassService onBehalfOf)
            throws InterruptedException;

    class Default implements ShellRunner {
        public static final String TES_AUTH_HEADER = "AWS_CONTAINER_AUTHORIZATION_TOKEN";
        public static final String GG_ROOT_CA_PATH = "GG_ROOT_CA_PATH";
        private static final String SCRIPT_NAME_KEY = "scriptName";

        @Inject
        NucleusPaths nucleusPaths;

        @Inject
        DeviceConfiguration deviceConfiguration;

        @Override
        public Exec setup(String note, String command, GreengrassService onBehalfOf) throws IOException {
            if (!isEmpty(command) && onBehalfOf != null) {
                Path cwd = nucleusPaths.workPath(onBehalfOf.getServiceName());
                Logger logger = getLoggerToUse(onBehalfOf);
                String rootCaPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
                if (rootCaPath == null) {
                    rootCaPath = "";
                }
                Exec exec = Platform.getInstance().createNewProcessRunner().withShell(command).withOut(s -> {
                    String ss = s.toString().trim();
                    logger.atInfo().setEventType("stdout").kv(SCRIPT_NAME_KEY, note).log(ss);
                }).withErr(s -> {
                    String ss = s.toString().trim();
                    logger.atWarn().setEventType("stderr").kv(SCRIPT_NAME_KEY, note).log(ss);
                })
                        .setenv("SVCUID",
                                String.valueOf(
                                        onBehalfOf.getPrivateConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY).getOnce()))
                        // Tes needs to inject identity separately as required by AWS SDK's which expect this env
                        // variable to be present for sending credential request to a server
                        .setenv(TES_AUTH_HEADER,
                                String.valueOf(
                                        onBehalfOf.getPrivateConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY).getOnce()))
                        .setenv(GG_ROOT_CA_PATH, rootCaPath)
                        .cd(cwd.toFile().getAbsoluteFile())
                        .logger(logger);

                if (ProxyUtils.getProxyConfiguration() != null) {
                    exec.setenv("HTTP_PROXY", ProxyUtils.getProxyEnvVarValue(deviceConfiguration))
                            .setenv("http_proxy", ProxyUtils.getProxyEnvVarValue(deviceConfiguration))
                            .setenv("HTTPS_PROXY", ProxyUtils.getProxyEnvVarValue(deviceConfiguration))
                            .setenv("https_proxy", ProxyUtils.getProxyEnvVarValue(deviceConfiguration))
                            .setenv("ALL_PROXY", ProxyUtils.getProxyEnvVarValue(deviceConfiguration))
                            .setenv("all_proxy", ProxyUtils.getProxyEnvVarValue(deviceConfiguration))
                            .setenv("NO_PROXY", ProxyUtils.getNoProxyEnvVarValue(deviceConfiguration))
                            .setenv("no_proxy", ProxyUtils.getNoProxyEnvVarValue(deviceConfiguration));
                }
                return exec;

            }
            return null;
        }

        private Logger getLoggerToUse(GreengrassService onBehalfOf) {
            Logger logger = onBehalfOf.logger;
            if (onBehalfOf instanceof GenericExternalService) {
                logger = ((GenericExternalService) onBehalfOf).separateLogger;
            }
            return logger;
        }

        @Override
        public boolean successful(Exec e, String note, IntConsumer background, GreengrassService onBehalfOf)
                throws InterruptedException {
            Logger logger = getLoggerToUse(onBehalfOf);
            logger.atInfo("shell-runner-start").kv(SCRIPT_NAME_KEY, note).kv("command", e.toString()).log();
            try {
                if (background == null) {
                    if (!e.successful(true)) {
                        logger.atWarn("shell-runner-error").kv(SCRIPT_NAME_KEY, note).kv("command", e.toString()).log();
                        return false;
                    }
                } else {
                    e.background(background);
                }
            } catch (IOException ex) {
                logger.atError("shell-runner-error")
                        .kv(SCRIPT_NAME_KEY, note)
                        .kv("command", e.toString())
                        .log("Error while running component lifecycle script", ex);
                return false;
            }
            return true;
        }
    }
}
