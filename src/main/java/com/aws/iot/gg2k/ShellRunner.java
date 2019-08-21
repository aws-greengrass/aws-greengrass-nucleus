/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.gg2k;

import com.aws.iot.config.*;
import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import javax.inject.*;

public interface ShellRunner {
    public abstract Exec run(String note, String command, IntConsumer background, GGService onBehalfOf);
    public static class Default implements ShellRunner {
        @Inject Log log;
        @Inject GG2K config;
        @Override
        public synchronized Exec run(String note, String command, IntConsumer background, GGService onBehalfOf) {
            if (!isEmpty(command) && onBehalfOf != null) {
                if (!isEmpty(note))
                    log.significant("run", note);
                int timeout = -1;
                Node n = onBehalfOf.config.getChild("bashtimeout");
                if (n instanceof Topic)
                    timeout = Coerce.toInt(((Topic) n).getOnce());
                if (timeout <= 0)
                    timeout = Coerce.toInt(config.lookup("system", "bashtimeout").dflt(120).getOnce());
                if (timeout <= 0) timeout = 120;
                Exec ret = new Exec().withShell(command)
                        .withOut(s -> {
                            log.note(note, s);
                            onBehalfOf.setStatus(s.toString());
                        })
                        .withErr(s -> {
                            log.warn(note, s);
                            onBehalfOf.setStatus(s.toString());
                        })
                        .withTimeout(timeout, TimeUnit.SECONDS)
                        .cd(config.workPath.toFile());
                if (background != null) {
                    ret.background(background);
                    return ret;
                }
                if (!ret.successful()) {
                    log.error("failed", command);
                    return Failed;
                }
            }
            return OK;
        }
    }
    public static final Exec OK = new Exec();
    public static final Exec Failed = new Exec();

    public static class Dryrun implements ShellRunner {
        @Inject Log log;
        @Override
        public synchronized Exec run(String note, String command, IntConsumer background, GGService onBehalfOf) {
            log.significant((background == null ? "# " : "# BG ") + note + "\n" + command);
            return OK;
        }
    }
}
