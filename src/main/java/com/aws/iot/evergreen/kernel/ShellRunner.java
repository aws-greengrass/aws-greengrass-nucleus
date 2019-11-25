/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.*;
import com.aws.iot.evergreen.util.*;
import static com.aws.iot.evergreen.util.Utils.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import javax.inject.*;

public interface ShellRunner {
    public abstract Exec setup(String note, String command, EvergreenService onBehalfOf);
    public abstract boolean successful(Exec e, String command, IntConsumer background);

    public static class Default implements ShellRunner {
        @Inject
        Log log;
        @Inject
        Kernel config;
        @Override
        public synchronized Exec setup(String note, String command, EvergreenService onBehalfOf) {
            if (!isEmpty(command) && onBehalfOf != null) {
                if (!isEmpty(note))
                    log.significant("run", note);
                Topic uid = onBehalfOf.config.createLeafChild("_UID");
                if(uid.getOnce()==null) uid.setValue(Utils.generateRandomString(16).toUpperCase());
                int timeout = -1;
                Node n = onBehalfOf.config.getChild("bashtimeout");
                if (n instanceof Topic)
                    timeout = Coerce.toInt(((Topic) n).getOnce());
                if (timeout <= 0)
                    timeout = Coerce.toInt(config.lookup("system", "bashtimeout").dflt(120).getOnce());
                if (timeout <= 0) timeout = 120;
                return new Exec().withShell(command)
                        .withOut(s -> {
                            String ss = s.toString().trim();
                            log.note(note, ss);
                            onBehalfOf.setStatus(ss);
                        })
                        .withErr(s -> {
                            String ss = s.toString().trim();
                            log.warn(note, ss);
                            onBehalfOf.setStatus(ss);
                        })
                        .withTimeout(timeout, TimeUnit.SECONDS)
                        .setenv("SVCUID",String.valueOf(uid.getOnce()))
                        .cd(config.workPath.toFile());
            }
            return null;
        }
        @Override
        public boolean successful(Exec e, String command, IntConsumer background) {
            if (background != null)
                e.background(background);
            else if (!e.successful(true)) {
                log.error("failed", command);
                return false;
            }
            return true;
        }
    }
    public static final Exec OK = new Exec();
    public static final Exec Failed = new Exec();

    public static class Dryrun implements ShellRunner {
        @Inject Log log;
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
