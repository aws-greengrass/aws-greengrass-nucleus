/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.NucleusPaths;

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
        private static final String SCRIPT_NAME_KEY = "scriptName";

        @Inject
        NucleusPaths nucleusPaths;

        @Override
        public synchronized Exec setup(String note, String command, GreengrassService onBehalfOf) throws IOException {
            if (!isEmpty(command) && onBehalfOf != null) {
                Path cwd = nucleusPaths.workPath(onBehalfOf.getServiceName());
                return new Exec()
                        .withShell(command)
                        .withOut(s -> {
                            String ss = s.toString().trim();
                            onBehalfOf.logger.atInfo().setEventType("shell-runner-stdout").kv(SCRIPT_NAME_KEY, note)
                                    .kv("stdout", ss).log();
                        })
                        .withErr(s -> {
                            String ss = s.toString().trim();
                            onBehalfOf.logger.atWarn().setEventType("shell-runner-stderr").kv(SCRIPT_NAME_KEY, note)
                                    .kv("stderr", ss).log();
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
                        .logger(onBehalfOf.logger);
            }
            return null;
        }

        @Override
        public boolean successful(Exec e, String note, IntConsumer background, GreengrassService onBehalfOf)
                throws InterruptedException {
            onBehalfOf.logger.atInfo("shell-runner-start").kv(SCRIPT_NAME_KEY, note).kv("command", e.toString()).log();
            try {
                if (background == null) {
                    if (!e.successful(true)) {
                        onBehalfOf.logger.atWarn("shell-runner-error").kv(SCRIPT_NAME_KEY, note)
                                .kv("command", e.toString()).log();
                        return false;
                    }
                } else {
                    e.background(background);
                }
            } catch (IOException ex) {
                onBehalfOf.logger.atError("shell-runner-error").kv(SCRIPT_NAME_KEY, note).kv("command", e.toString())
                        .log("Error while running process", ex);
                return false;
            }
            return true;
        }
    }
}
