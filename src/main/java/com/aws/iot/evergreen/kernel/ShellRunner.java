/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.Log;

import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.handler.AuthHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.iot.evergreen.util.Utils.isEmpty;

public interface ShellRunner {
    Exec OK = new Exec();
    Exec Failed = new Exec();

    Exec setup(String note, String command, EvergreenService onBehalfOf);

    boolean successful(Exec e, String command, IntConsumer background);

    class Default implements ShellRunner {
        @Inject
        Log log;
        @Inject
        Kernel config;

        @Override
        public synchronized Exec setup(String note, String command, EvergreenService onBehalfOf) {
            if (!isEmpty(command) && onBehalfOf != null) {
                if (!isEmpty(note) && log != null /* !!?!! */) {
                    log.significant("run", note);
                }
                int timeout = -1;
                Node n = onBehalfOf.config.getChild("bashtimeout");
                if (n instanceof Topic) {
                    timeout = Coerce.toInt(((Topic) n).getOnce());
                }
                if (timeout <= 0) {
                    if (config == null) {
                        System.err.println("CONFIG==NULL: " + command);
                        timeout = 120;
                        new Throwable("NULL").printStackTrace();
                    } else {
                        timeout = Coerce.toInt(config.lookup("system", "bashtimeout").dflt(120).getOnce());
                    }
                }
                if (timeout <= 0) {
                    timeout = 120;
                }
                return new Exec().withShell(command).withOut(s -> {
                    String ss = s.toString().trim();
                    log.note(note, ss);
                    onBehalfOf.setStatus(ss);
                }).withErr(s -> {
                    String ss = s.toString().trim();
                    log.warn(note, ss);
                    onBehalfOf.setStatus(ss);
                }).withTimeout(timeout, TimeUnit.SECONDS).setenv("SVCUID",
                        String.valueOf(onBehalfOf.config.findLeafChild(SERVICE_UNIQUE_ID_KEY).getOnce())).cd(config.workPath.toFile());
            }
            return null;
        }

        @Override
        public boolean successful(Exec e, String command, IntConsumer background) {
            if (background != null) {
                e.background(background);
            } else if (!e.successful(true)) {
                log.error("failed", command);
                return false;
            }
            return true;
        }
    }

    class Dryrun implements ShellRunner {
        @Inject
        Log log;

        @Override
        public synchronized Exec setup(String note, String command, EvergreenService onBehalfOf) {
            log.significant("# " + note + "\n" + command);
            return OK;
        }

        @Override
        public boolean successful(Exec e, String command, IntConsumer background) {
            return true;
        }
    }
}
