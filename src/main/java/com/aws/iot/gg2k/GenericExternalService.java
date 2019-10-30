/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.gg2k;

import com.aws.iot.config.Node;
import com.aws.iot.config.Topic;
import com.aws.iot.config.Topics;
import com.aws.iot.util.Coerce;
import com.aws.iot.util.Exec;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.aws.iot.dependency.State.*;

public class GenericExternalService extends EvergreenService {
    public GenericExternalService(com.aws.iot.config.Topics c) {
        super(c);
    }
    @Override
    public void install() {
//        log().significant("install", this);
        run("install", null);
        super.install();
    }
    @Override
    public void awaitingStartup() {
//        log().significant("awaitingStartup", this);
        run("awaitingStartup", null);
        super.awaitingStartup();
    }
    @Override
    public void startup() {
//        log().significant("startup", this);
        if(run("startup", null)==RunStatus.Errored)
            setState(Errored);
        super.startup();
    }
    @Override
    public void run() {
//        log().significant("running", this);
        if (run("run", exit -> {
            if (exit == 0) {
                setState(Finished);
                log().significant("Finished", getName());
            } else {
                setState(Errored);
                log().error("Failed", getName(), exit);
            }
        })==RunStatus.NothingDone) {
            log().significant("run: NothingDone", getName());
            setState(Finished);
        }
    }

    @Override
    public void shutdown() {
//        log().significant("shutdown", this);
        run("shutdown", null);
    }

    protected RunStatus run(String name, IntConsumer background) {
        Node n = pickByOS(name);
        if(n==null) {
//            if(required) log().warn("Missing",name,this);
            return RunStatus.NothingDone;
        }
        return run(n, background);
    }

    protected RunStatus run(Node n, IntConsumer background) {
        return n instanceof Topic ? run((Topic) n, background, null)
             : n instanceof Topics ? run((Topics) n, background)
                : RunStatus.Errored;
    }

    protected RunStatus run(Topic t, IntConsumer background, Topics config) {
        return run(t, Coerce.toString(t.getOnce()), background, config);
    }
    protected RunStatus run(Topic t, String cmd, IntConsumer background, Topics config) {
        ShellRunner shellRunner = context.get(ShellRunner.class);
        EZTemplates templateEngine = context.get(EZTemplates.class);
        cmd = templateEngine.rewrite(cmd).toString();
        setStatus(cmd);
        if(background==null) setStatus(null);
//        RunStatus OK = shellRunner.setup(t.getFullName(), cmd, background, this, null) != ShellRunner.Failed
//                ? RunStatus.OK : RunStatus.Errored;
        Exec exec = shellRunner.setup(t.getFullName(), cmd, this);
        if(exec!=null) { // there's something to run
            addEnv(exec, t.parent);
            log().significant(this,"exec",cmd);
            return shellRunner.successful(exec, cmd, background)
                    ? RunStatus.OK : RunStatus.Errored;
        }
        else return RunStatus.NothingDone;
    }

    private static final Pattern skipcmd = Pattern.compile("(exists|onpath) +(.+)");
    boolean shouldSkip(Topics n) {
        Node skipif = n.getChild("skipif");
        boolean neg = skipif == null && (skipif = n.getChild("doif")) != null;
        if (skipif instanceof Topic) {
            Topic tp = (Topic) skipif;
            String expr = String.valueOf(tp.getOnce()).trim();
            if (expr.startsWith("!")) {
                expr = expr.substring(1).trim();
                neg = !neg;
            }
            expr = context.get(EZTemplates.class).rewrite(expr).toString();
            Matcher m = skipcmd.matcher(expr);
            if (m.matches())
                switch (m.group(1)) {
                    case "onpath":
                        return Exec.which(m.group(2)) != null ^ neg; // XOR ?!?!
                    case "exists":
                        return Files.exists(Paths.get(context.get(Kernel.class).deTilde(m.group(2)))) ^ neg;
                    case "true": return !neg;
                    default:
                        errored("Unknown operator", m.group(1));
                        return false;
                }
            // Assume it's a shell script: test for 0 return code and nothing on stderr
            return neg ^ (run(tp, expr, null, n)!=RunStatus.Errored);
        }
        return false;
    }

    protected RunStatus run(Topics t, IntConsumer background) {
        if (!shouldSkip(t)) {
            Node script = t.getChild("script");
            if (script instanceof Topic)
                return run((Topic) script, background, t);
            else {
                errored("Missing script: for ", t.getFullName());
                return RunStatus.Errored;
            }
        }
        else {
            log().significant("Skipping", t.getFullName());
            return RunStatus.OK;
        }
    }

    private void addEnv(Exec exec, Topics src) {
        if(src!=null) {
            addEnv(exec, src.parent); // add parents contributions first
            Node env = src.getChild("setenv");
            if(env instanceof Topics) {
                EZTemplates templateEngine = context.get(EZTemplates.class);
                ((Topics)env).forEach(n->{
                    if(n instanceof Topic)
                        exec.setenv(n.name, templateEngine.rewrite(Coerce.toString(((Topic)n).getOnce())));
                });
            }
        }
    }
}
