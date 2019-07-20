/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.gg2k;

import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.*;
import java.util.function.*;
import javax.inject.*;

public interface ShellRunner {
    public abstract Exec run(String note, String command, IntConsumer background);
    public static class Default implements ShellRunner {
        @Inject Log log;
        @Inject GG2K config;
        @Override
        public synchronized Exec run(String note, String command, IntConsumer background) {
            if(!isEmpty(command)) {
                if(!isEmpty(note))
                    log.significant("run",note);
                Exec ret = new Exec().withShell(command)
                        .withOut(s->log.significant("stdout",s))
                        .withErr(s->log.warn("stderr",s))
                        .cd(config.workPath.toFile());
                if(background!=null) {
                    ret.background(background);
                    return ret;
                }
                if(!ret.successful()) {
                    log.error("failed",command);
                    return Failed;
                } return OK;
            }
            return OK;
        }
    }
    public static final Exec OK = new Exec();
    public static final Exec Failed = new Exec();
    public static class Dryrun implements ShellRunner {
        @Inject Log log;
        @Override
        public synchronized Exec run(String note, String command, IntConsumer background) {
            System.out.println((background==null ? "# " : "# BG ")+note+"\n"+command);
            return OK;
        }
    }
}
