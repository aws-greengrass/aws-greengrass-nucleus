/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.platforms.android.AndroidApkInstallerExec;
import com.aws.greengrass.util.platforms.android.AndroidComponentExec;
import com.aws.greengrass.util.platforms.android.AndroidShellExec;

import java.io.IOException;
import java.nio.file.Path;

import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.util.Utils.isEmpty;

public class AndroidRunner extends ShellRunner.Default {

    @Override
    public synchronized Exec setup(String note, String command, GreengrassService onBehalfOf) throws IOException {
        if (!isEmpty(command) && onBehalfOf != null) {
            Path cwd = nucleusPaths.workPath(onBehalfOf.getServiceName());
            Logger logger = getLoggerToUse(onBehalfOf);
            Exec exec = createSpecializedExec(command)
                    .withShell(command)
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

    /**
     * Factory of Exec specialized for Android commands.
     *
     * @param command command to run
     * @return specialized Exec instance
     */
    private Exec createSpecializedExec(String command) {
        if (command.startsWith("#install_package") && command.startsWith("#uninstall_package")) {
            // handle commands to install/uninstall apk
            // command format: "#install_package path_to.apk [force=true|false]"
            //  must pass also parent GreengrassService or at least packageName
            return new AndroidApkInstallerExec();
        } else if (command.startsWith("#startup_service")
                || command.startsWith("#shutdown_service")
                || command.startsWith("#run_service")) {
            // handle commands to start/shutdown or run application as Android Foreground Service
            // format of command: "#startup_service [[packageName].ClassName] [StartIntent]"
            // format of command: "#run_service [[packageName].ClassName] [StartIntent]"
            //  must pass also parent GreengrassService or at least packageName

            // format of command: "#shutdown_service"
            //  must pass also parent GreengrassService or at least packageName
            return new AndroidComponentExec();
        } else {
            // handle run Android shell commands (currently useful for debugging)
            return new AndroidShellExec();
        }
    }
}
