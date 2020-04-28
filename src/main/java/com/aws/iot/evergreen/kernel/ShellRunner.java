/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.util.Exec;

import java.util.function.IntConsumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.AuthHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.iot.evergreen.util.Utils.isEmpty;

public interface ShellRunner {

    Exec setup(String note, String command, EvergreenService onBehalfOf);

    boolean successful(Exec e, String note, IntConsumer background, EvergreenService onBehalfOf)
            throws InterruptedException;

    class Default implements ShellRunner {
        private static final String SCRIPT_NAME_KEY = "scriptName";

        @Inject
        Kernel config;

        @Override
        public synchronized Exec setup(String note, String command, EvergreenService onBehalfOf) {
            if (!isEmpty(command) && onBehalfOf != null) {
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
                        .cd(config.getWorkPath().toFile());
            }
            return null;
        }

        @Override
        public boolean successful(Exec e, String note, IntConsumer background, EvergreenService onBehalfOf)
                throws InterruptedException {
            onBehalfOf.logger.atInfo().setEventType("shell-runner-start").kv(SCRIPT_NAME_KEY, note)
                    .kv("script", e.toString()).log();
            if (background == null) {
                if (!e.successful(true)) {
                    onBehalfOf.logger.atWarn().setEventType("shell-runner-error").kv("command", e.toString()).log();
                    return false;
                }
            } else {
                e.background(background);
            }
            return true;
        }
    }
}
