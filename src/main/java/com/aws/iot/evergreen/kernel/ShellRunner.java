/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Exec;

import java.util.function.IntConsumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.AuthHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.iot.evergreen.util.Utils.isEmpty;

public interface ShellRunner {

    Exec setup(String note, String command, EvergreenService onBehalfOf);

    boolean successful(Exec e, String command, IntConsumer background) throws InterruptedException;

    class Default implements ShellRunner {
        private static final Logger logger = LogManager.getLogger(ShellRunner.class);

        @Inject
        Kernel config;

        @Override
        public synchronized Exec setup(String note, String command, EvergreenService onBehalfOf) {
            if (!isEmpty(command) && onBehalfOf != null) {
                if (!isEmpty(note) && logger != null /* !!?!! */) {
                    logger.atInfo().setEventType("shell-runner-start").addKeyValue("scriptName", note)
                            .addKeyValue("serviceName", onBehalfOf.getName()).log();
                }
                return new Exec().withShell(command).withOut(s -> {
                    String ss = s.toString().trim();
                    logger.atInfo().setEventType("shell-runner-stdout").addKeyValue("scriptName", note)
                            .addKeyValue("serviceName", onBehalfOf.getName()).addKeyValue("stdout", ss).log();
                }).withErr(s -> {
                    String ss = s.toString().trim();
                    logger.atWarn().setEventType("shell-runner-stderr").addKeyValue("scriptName", note)
                            .addKeyValue("serviceName", onBehalfOf.getName()).addKeyValue("stderr", ss).log();
                }).setenv("SVCUID",
                        String.valueOf(onBehalfOf.config.findLeafChild(SERVICE_UNIQUE_ID_KEY).getOnce()))
                        .cd(config.workPath.toFile());
            }
            return null;
        }

        @Override
        public boolean successful(Exec e, String command, IntConsumer background) throws InterruptedException {
            if (background != null) {
                e.background(background);
            } else if (!e.successful(true)) {
                logger.atWarn().setEventType("shell-runner-error").addKeyValue("command", command).log();
                return false;
            }
            return true;
        }
    }

    class Dryrun implements ShellRunner {
        private static final Logger logger = LogManager.getLogger(ShellRunner.class);

        @Override
        public synchronized Exec setup(String note, String command, EvergreenService onBehalfOf) {
            logger.atInfo().setEventType("shell-dryrun").addKeyValue("name", note).addKeyValue("command", command)
                    .log();
            return new Exec();
        }

        @Override
        public boolean successful(Exec e, String command, IntConsumer background) {
            return true;
        }
    }
}
