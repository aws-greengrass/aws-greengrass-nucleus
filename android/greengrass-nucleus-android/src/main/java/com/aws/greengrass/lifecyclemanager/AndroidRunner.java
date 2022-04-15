/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidCallable;
import com.aws.greengrass.util.platforms.android.AndroidCallableExec;
import com.aws.greengrass.util.platforms.android.AndroidShellExec;

import java.io.IOException;
import java.nio.file.Path;

import static com.aws.greengrass.android.managers.AndroidBaseComponentManager.RUN_SERVICE_CMD;
import static com.aws.greengrass.android.managers.AndroidBaseComponentManager.SHUTDOWN_SERVICE_CMD;
import static com.aws.greengrass.android.managers.AndroidBaseComponentManager.STARTUP_SERVICE_CMD;
import static com.aws.greengrass.android.managers.AndroidBasePackageManager.APK_INSTALL_CMD;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.util.Utils.isEmpty;

public class AndroidRunner extends ShellRunner.Default {

    @Override
    public synchronized Exec setup(String note, String command, GreengrassService onBehalfOf)
            throws IOException {
        Exec exec = null;
        if (!isEmpty(note) && !isEmpty(command) && onBehalfOf != null) {
            String serviceName = onBehalfOf.getServiceName();
            Path cwd = nucleusPaths.workPath(serviceName);
            Logger logger = getLoggerToUse(onBehalfOf);
            try {
                exec = createSpecializedExec(command, serviceName, logger);
                exec.withOut(s -> {
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
            } catch (Throwable e) {
                if (exec != null) {
                    exec.close();
                    throw e;
                }
            }
        }
        return exec;
    }

    /**
     * Factory of Exec specialized for Android commands.
     *
     * @param command command to run
     * @param packageName name of package
     * @param logger service logger to use
     * @return specialized Exec instance which is partially initialized by specific methods
     * @throws IOException on errors
     */
    private Exec createSpecializedExec(String command, String packageName,
                                       Logger logger) throws IOException {
        // TODO: implement "#uninstall_package" too
        if (command.startsWith(APK_INSTALL_CMD)) {
            // handle commands to install/uninstall apk
            // command format: "#install_package path_to.apk [force[=true|false]]"
            AndroidCallable installer = Platform.getInstance()
                    .getAndroidPackageManager()
                    .getApkInstaller(command, packageName, logger);
            AndroidCallableExec exec = new AndroidCallableExec();
            exec.withCallable(installer, command);
            return exec;
        } else if (command.startsWith(RUN_SERVICE_CMD)) {
            // format of command: "#run_service [[[Package].ClassName] [StartIntent]] [-- Arg1 Arg2 ...]"
            AndroidCallable runner = Platform.getInstance()
                    .getAndroidComponentManager()
                    .getComponentRunner(command, packageName, logger);
            AndroidCallableExec exec = new AndroidCallableExec();
            exec.withCallable(runner, command);
            return exec;
        } else if (command.startsWith(STARTUP_SERVICE_CMD)) {
            // format of command: "#startup_service [[[Package].ClassName] [StartIntent]]] [-- Arg1 Arg2 ...]"
            Pair<AndroidCallable, AndroidCallable> starterAndStopper = Platform.getInstance()
                    .getAndroidComponentManager()
                    .getComponentStarterAndStopper(command, packageName, logger);
            // here command already parsed by getComponentStarter()
            AndroidCallableExec exec = new AndroidCallableExec();
            exec.withCallable(starterAndStopper.getLeft(), command);
            exec.withClose(starterAndStopper.getRight());
            return exec;
        } else if (command.startsWith(SHUTDOWN_SERVICE_CMD)) {
            // format of command: "#shutdown_service [[packageName].ClassName]"
            AndroidCallable stopper = Platform.getInstance()
                    .getAndroidComponentManager()
                    .getComponentStopper(command, packageName, logger);
            AndroidCallableExec exec = new AndroidCallableExec();
            exec.withCallable(stopper, command);
            return exec;
        } else {
            // handle run Android shell commands (currently useful for debugging)
            AndroidShellExec exec = new AndroidShellExec();
            exec.withShell(command);
            return exec;
        }
    }
}
