/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.Utils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.AuthHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.iot.evergreen.util.Utils.isEmpty;

public interface ShellRunner {

    Exec setup(String note, String command, EvergreenService onBehalfOf) throws IOException;

    boolean successful(Exec e, String note, IntConsumer background, EvergreenService onBehalfOf)
            throws InterruptedException;

    class Default implements ShellRunner {
        private static final String SCRIPT_NAME_KEY = "scriptName";

        @Inject
        Kernel config;

        @Override
        public synchronized Exec setup(String note, String command, EvergreenService onBehalfOf) throws IOException {
            if (!isEmpty(command) && onBehalfOf != null) {
                Path cwd = config.getWorkPath().resolve(onBehalfOf.getName());
                Utils.createPaths(cwd);
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
                                String.valueOf(onBehalfOf.getServiceConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY)
                                        .getOnce()))
                        .cd(cwd.toFile().getAbsoluteFile());
            }
            return null;
        }

        @Override
        public boolean successful(Exec e, String note, IntConsumer background, EvergreenService onBehalfOf)
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
