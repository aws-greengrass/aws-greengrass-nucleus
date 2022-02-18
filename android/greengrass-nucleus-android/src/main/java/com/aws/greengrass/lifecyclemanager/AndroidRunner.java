/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.util.Utils.isEmpty;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.nio.file.Path;

public class AndroidRunner extends ShellRunner.Default {

    @Override
    public synchronized Exec setup(String note, String command, GreengrassService onBehalfOf) throws IOException {
        if (!isEmpty(command) && onBehalfOf != null) {
            Path cwd = nucleusPaths.workPath(onBehalfOf.getServiceName());
            Logger logger = getLoggerToUse(onBehalfOf);
            //FIXME: analyze command to determine desired Exec type and create it with createNewProcessRunner(type)
            //FIXME: createNewProcessRunner(type) method exists only on Android platform. Ensure Platform.getInstance() returns instance of AndroidPlatform class
            Exec exec = Platform.getInstance().createNewProcessRunner()
                    .withExec(command)
                    .withOut(s -> {
                        String ss = s.toString().trim();
                        logger.atInfo().setEventType("stdout").kv(SCRIPT_NAME_KEY, note).log(ss);
                    })
                    .withErr(s -> {
                        String ss = s.toString().trim();
                        logger.atWarn().setEventType("stderr").kv(SCRIPT_NAME_KEY, note).log(ss);
                    })
                    .setenv("SVCUID",
                            String.valueOf(onBehalfOf.getPrivateConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY)
                                    .getOnce()))
                    // Tes needs to inject identity separately as required by AWS SDK's which expect this env
                    // variable to be present for sending credential request to a server
                    .setenv(TES_AUTH_HEADER,
                            String.valueOf(onBehalfOf.getPrivateConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY)
                                    .getOnce()))
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
}
