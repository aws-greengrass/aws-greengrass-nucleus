/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Exec;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenericExternalService extends EvergreenService {
    public GenericExternalService(Topics c) {
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
            setState(State.Errored);
        super.startup();
    }
    @Override
    public void run() {
//        log().significant("running", this);
        if (run("run", exit -> {
            if (getState() != State.Running) {
                // If the state changed, means some other handler already handles the state transition. Eg. shutdown()
                // Don't try to set state in this case.
                if (exit != 0)
                    context.getLog().error("Error in running ", getName(), exit);
                return;
            }
            if (exit == 0) {
                setState(State.Finished);
                context.getLog().significant("Finished", getName());
            } else {
                setState(State.Errored);
                context.getLog().error("Failed", getName(), exit);
            }
        })==RunStatus.NothingDone) {
            context.getLog().significant("run: NothingDone", getName());
            setState(State.Finished);
        }
    }

    @Override
    public void shutdown() {
        run("shutdown", null);
        super.shutdown();
        if (runningExec != null && !runningExec.terminated()){
            getContext().getLog().error("Service not shutdown. Force kill: " + getName());
            // TODO: Send SIGTERM, wait for a timeout, recheck and send SIGKILL.
            runningExec.terminateForcibly();
        }
    }

    protected RunStatus run(String name, IntConsumer background) {
        Node n = pickByOS(name);
        if(n==null) {
//            if(required) context.getLog().warn("Missing",name,this);
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

    private Exec runningExec = null;
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
            context.getLog().significant(this,"exec",cmd);
            runningExec = exec;
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
            context.getLog().significant("Skipping", t.getFullName());
            return RunStatus.NothingDone;
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
