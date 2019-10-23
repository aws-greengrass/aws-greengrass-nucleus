/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.gg2k;

import com.aws.iot.config.*;
import com.aws.iot.config.WhatHappened;
import com.aws.iot.config.Node;
import com.aws.iot.config.Topic;
import com.aws.iot.config.Topics;
import com.aws.iot.dependency.*;
import static com.aws.iot.dependency.State.*;
import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.getUltimateCause;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.regex.*;
import javax.inject.*;


public class GGService implements InjectionActions, Subscriber, Closeable {
    private final Topic state;
    private Throwable error;
    protected ConcurrentHashMap<GGService, State> dependencies;
    private Future backingTask;
    public Context context;
    public static State getState(GGService o) {
        return o.getState();
    }
    public State getState() {
        return (State)state.getOnce();
    }
    public boolean inState(State s) {
        return s==state.getOnce();
    }
    public Topic getStateTopic() {
        return state;
    }
    public Log log() { return context.get(Log.class); }
    public boolean isRunningInternally() {
        Future b = backingTask;
        return b!=null && !b.isDone();
    }
    private boolean errorHandlerErrored; // cheezy hack to avoid repeating error handlers
    public void setState(State s) {
//        if(s==Errored)
//            new Exception("Set to Errored").printStackTrace();
        state.setValue(Long.MAX_VALUE, s);
        context.notify(this,s);
    }
    private State activeState = State.New;
    @Override // for listening to state changes
    public void published(final WhatHappened what, final Topic topic) {
        final State newState = (State) topic.getOnce();
        if(activeState.isRunning() && !newState.isRunning()) { // transition from running to not running
            try {
                shutdown();
                Future b = backingTask;
                if(b!=null) {
                    backingTask = null;
                    b.cancel(true);
                }
            } catch (Throwable t) {
                errored("Failed shutting down", t);
            }
        }
        try {
            switch(newState) {
                case Installing:
                    setBackingTask(() -> {
                        try {
                            install();
                            setState(AwaitingStartup);
                        } catch (Throwable t) {
                            errored("Failed installing up", t);
                        }
                        backingTask = null;
                    }, getName()+" => "+newState);
                    break;
                case AwaitingStartup:
                    awaitingStartup();
                    break;
                case Starting:
                    setBackingTask(() -> {
                        try {
                            timer = Periodicity.of(this);
                            startup();
                            if(!errored()) setState(timer==null  // Let timer do the transition to Running==null
                                    ? State.Running
                                    : State.Finished);
                        } catch (Throwable t) {
                            errored("Failed starting up", t);
                        }
                        backingTask = null;
                    }, getName()+" => "+newState);
                    break;
                case Running:
                    if(activeState != Unstable) {
                        recheckOthersDependencies();
                        setBackingTask(() -> {
                            try {
                                run();
                                if(!errored()) setState(State.Finished);
                            } catch (Throwable t) {
                                errored("Failed starting up", t);
                            }
                            backingTask = null;
                        }, getName()+" => "+newState);
                    }
                    break;
                case Errored:
                    try {
                        if(!errorHandlerErrored) handleError();
                    } catch (Throwable t) {
                        errorHandlerErrored = true;
                        errored("Error handler failed", t);
                    }
                    break;
            }
        } catch(Throwable t) {
            errored("Transitioning from "+getName()+" => "+newState, t);
        }
        activeState = newState;
    }
    private synchronized void setBackingTask(Runnable r, String db) {
        Future bt = backingTask;
        if(bt!=null) {
            backingTask = null;
            if(!bt.isDone()) {
                System.out.println("Cancelling "+db);
                bt.cancel(true);
            }
        }
        if(r!=null)
            backingTask = context.get(ExecutorService.class).submit(r);
    }
    static final void setState(Object o, State st) {
        if (o instanceof GGService)
            ((GGService) o).setState(st);
    }
    public void errored(String message, Throwable e) {
//        e.printStackTrace();
        e = getUltimateCause(e);
        error = e;
        errored(message, (Object)e);
    }
    public void errored(String message, Object e) {
        if(context==null) {
            System.err.println("ERROR EARLY IN BOOT\n\t"+message+" "+e);
            if(e instanceof Throwable) ((Throwable)e).printStackTrace(System.err);
        }
        else log().error(this,message,e);
        setState(State.Errored);
    }
    public boolean errored() {
        return !getState().isHappy() || error != null;
    }
    /**
     * Called when this service is known to be needed to make sure that required
     * additional software is installed.
     */
    protected void install() {
    }
    /**
     * Called when this service is known to be needed, and is AwaitingStartup.
     * This is a good place to do any preconfiguration.  It is seperate from "install"
     * because there are situations (like factory preflight setup) where there's a
     * certain amount of setup to be done, but we're not actually going to start the app.
     */
    protected void awaitingStartup() {
        if(!hasDependencies() && !errored()) setState(Starting);
    }
    /**
     * Called when all dependencies are Running. If there are no dependencies,
     * it is called right after postInject.  The service doesn't transition to Running
     * until *after* this state is complete.
     */
    Periodicity timer;
    public void startup() {
    }
    /**
     * Called when all dependencies are Running. If there are no dependencies,
     * it is called right after postInject.
     */
    protected void run() {
    }
    /**
     * Called when a running service encounters an error.
     */
    protected void handleError() {
    }
    /**
     * Called when the object's state leaves Running.
     * To shutdown a service, use <tt>setState(Finished)</dd>
     */
    public void shutdown() {
        Periodicity t = timer;
        if(t!=null) t.shutdown();
    }
    /**
     * Sets the state to Shutdown
     */
    @Override
    public void close() {
        setState(State.Shutdown);
    }
    public Context getContext() { return context; }
    public void addDependency(GGService v, State when) {
        if (dependencies == null)
            dependencies = new ConcurrentHashMap<>();
//        System.out.println(getName()+" depends on "+v.getName());
        dependencies.put(v, when);
    }
    private boolean hasDependencies() {
//        if(dependencies==null) {
//            System.out.println(getName()+": no dependencies");
//            return false;
//        } else {
//            dependencies.entrySet().stream().forEach(ls -> {
//                System.out.println(getName() +"/"+ getState()+" :: "+ls.getKey().getName()+"/"+ls.getKey().getState());
//            });
//        }
        return dependencies != null
                && (dependencies.entrySet().stream().anyMatch(ls -> ls.getKey().getState().preceeds(ls.getValue())));
    }
    public void forAllDependencies(Consumer<? super GGService> f) {
        if(dependencies!=null) dependencies.keySet().forEach(f);
    }
//    private CopyOnWriteArrayList<stateChangeListener> listeners;
//    public synchronized void addStateListener(stateChangeListener l) {
//        if(listeners==null) listeners = new CopyOnWriteArrayList<>();
//        listeners.add(l);
//    }
//    public synchronized void removeStateListener(stateChangeListener l) {
//        if(listeners!=null) {
//            listeners.remove(l);
//            if(listeners.isEmpty()) listeners = null;
//        }
//    }
//    private void notify(GGService l, State was) {
//        if(listeners!=null)
//            listeners.forEach(s->s.stateChanged(l,was));
//        context.notify(l, was);
//    }
    private void recheckOthersDependencies() {
        if (context != null) {
            final AtomicBoolean changed = new AtomicBoolean(true);
            while (changed.get()) {
                changed.set(false);
                context.forEach(v -> {
                    Object vv= v.value;
                    if(vv instanceof GGService) {
                        GGService l = (GGService) vv;
                        if (l.inState(AwaitingStartup)) {
                            if (!l.hasDependencies()) {
                                l.setState(State.Starting);
                                changed.set(true);
                            }
                        }
                    }
                });
            }
        }
    }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String s) { status = s; }
    public interface stateChangeListener {
        void stateChanged(GGService l, State was);
    }
    public final Topics config;
    protected final CopyOnWriteArrayList<GGService> explicitDependencies = new CopyOnWriteArrayList<>();
    @SuppressWarnings("LeakingThisInConstructor")
    public GGService(Topics c) {
        config = c;
        state = c.createLeafChild("_State");
        state.setValue(Long.MAX_VALUE, State.New);
        state.validate((n,o)->{
            State s = Coerce.toEnum(State.class, n);
            return s==null ? o : n;});
        state.subscribe(this);
    }
    public String getName() {
        return config==null ? getClass().getSimpleName() : config.getFullName();
    }
    @Override
    public void postInject() {
//        addDependency(config.getChild("dependencies"));   // possible synonyms
//        addDependency(config.getChild("dependency"));
//        addDependency(config.getChild("defautimpl"));
        addDependency(config.getChild("requires"));
    }
    public boolean addDependency(Node d) {
        boolean ret = false;
        if (d instanceof Topics)
            d = pickByOS((Topics) d);
        if (d instanceof Topic) {
            String ds = ((Topic) d).getOnce().toString();
            Matcher m = depParse.matcher(ds);
            while(m.find()) {
                addDependency(m.group(1), m.group(3));
                ret = true;
            }
            if (!m.hitEnd())
                errored("bad dependency syntax", ds);
        }
        return ret;
    }
    public void addDependency(String name, String startWhen) {
        if (startWhen == null)
            startWhen = State.Running.toString();
        State x = null;
        if (startWhen != null) {
            int len = startWhen.length();
            if (len > 0) {
                // do "friendly" match
                for (State s : State.values())
                    if (startWhen.regionMatches(true, 0, s.name(), 0, len)) {
                        x = s;
                        break;
                    }
                if (x == null)
                    errored("does not match any GGService state", startWhen);
            }
        }
        addDependency(name, x == null ? State.Running : x);
    }
    public void addDependency(String name, State startWhen) {
        try {
            GGService d = locate(context, name);
            if (d != null) {
                explicitDependencies.add(d);
                addDependency(d, startWhen);
            }
            else
                errored("Couldn't locate", name);
        } catch (Throwable ex) {
            errored("Failure adding dependency", ex);
            ex.printStackTrace(System.out);
        }
    }
    private static final Pattern depParse = Pattern.compile(" *([^,:;& ]+)(:([^,; ]+))?[,; ]*");
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        try {
            if (config == null)
                sb.append("[nameless]");
            else
                config.appendNameTo(sb);
            if (!inState(Running))
                sb.append(':').append(getState().toString());
        } catch (IOException ex) {
            sb.append(ex.toString());
        }
        return sb.toString();
    }
    public static GGService locate(Context context, String name) throws Throwable {
        return context.getv(GGService.class, name).computeIfEmpty(v->{
            Configuration c = context.get(Configuration.class);
            Topics t = c.lookupTopics(Configuration.splitPath(name));
            assert(t!=null);
            GGService ret;
            Class clazz = null;
            Node n = t.getChild("class");
            if (n != null) {
                String cn = Coerce.toString(n);
                try {
                    clazz = Class.forName(cn);
                } catch (Throwable ex) {
                    ex.printStackTrace(System.out);
                    return errNode(context, name, "creating code-backed service from " + cn, ex);
                }
            }
            if(clazz==null) {
                Map<String,Class> si = context.getIfExists(Map.class, "service-implementors");
                if(si!=null) clazz = si.get(name);
            }
            if(clazz!=null) {
                try {
                    Constructor ctor = clazz.getConstructor(Topics.class);
                    ret = (GGService) ctor.newInstance(t);
                    if(clazz.getAnnotation(Singleton.class) !=null) {
                        context.put(ret.getClass(), v);
                    }
                } catch (Throwable ex) {
                    ex.printStackTrace(System.out);
                    ret = errNode(context, name, "creating code-backed service from " + clazz.getSimpleName(), ex);
                }
            }
            else if(t.isEmpty())
                ret = errNode(context, name, "No matching definition in system model", null);
            else
                try {
                    ret = new GenericExternalService(t);
                } catch (Throwable ex) {
                    ret = errNode(context, name, "Creating generic service", ex);
                }
            return ret;
        });
    }
    public static GGService errNode(Context context, String name, String message, Throwable ex) {
        try {
            context.get(Log.class).error("Error locating service",name,message,ex);
            GGService ggs = new GenericExternalService(Topics.errorNode(name,
                    "Error locating service " + name + ": " + message
                            + (ex == null ? "" : "\n\t" + ex)));
            return ggs;
        } catch (Throwable ex1) {
            context.get(Log.class).error(name,message,ex);
            return null;
        }
    }
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
            expr = templateEngine.rewrite(expr).toString();
            Matcher m = skipcmd.matcher(expr);
            if (m.matches())
                switch (m.group(1)) {
                    case "onpath":
                        return Exec.which(m.group(2)) != null ^ neg; // XOR ?!?!
                    case "exists":
                        return Files.exists(Paths.get(context.get(GG2K.class).deTilde(m.group(2)))) ^ neg;
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
    private static final Pattern skipcmd = Pattern.compile("(exists|onpath) +(.+)");
    Node pickByOS(String name) {
        Node n = config.getChild(name);
        if (n instanceof Topics)
            n = pickByOS((Topics) n);
        return n;
    }
    private static final HashMap<String, Integer> ranks = new HashMap<>();
    public static int rank(String s) {
        Integer i = ranks.get(s);
        return i == null ? -1 : i;
    }
    static {
        // figure out what OS we're running and add applicable tags
        // The more specific a tag is, the higher its rank should be
        // TODO: a loopy set of hacks
        ranks.put("all", 0);
        ranks.put("any", 0);
        if (Files.exists(Paths.get("/bin/bash"))) {
            ranks.put("unix", 3);
            ranks.put("posix", 3);
        }
        if (Files.exists(Paths.get("/usr/bin/bash")))
            ranks.put("posix", 3);
        if (Files.exists(Paths.get("/proc")))
            ranks.put("linux", 10);
        if (Files.exists(Paths.get("/usr/bin/apt-get")))
            ranks.put("debian", 11);
        if (Exec.isWindows)
            ranks.put("windows", 5);
        if (Files.exists(Paths.get("/usr/bin/yum")))
            ranks.put("fedora", 11);
        String sysver = Exec.sh("uname -a").toLowerCase();
        if (sysver.contains("ubuntu"))
            ranks.put("ubuntu", 20);
        if (sysver.contains("darwin"))
            ranks.put("macos", 20);
        if (sysver.contains("raspbian"))
            ranks.put("raspbian", 22);
        if (sysver.contains("qnx"))
            ranks.put("qnx", 22);
        if (sysver.contains("cygwin"))
            ranks.put("cygwin", 22);
        if (sysver.contains("freebsd"))
            ranks.put("freebsd", 22);
        if (sysver.contains("solaris") || sysver.contains("sunos"))
            ranks.put("solaris", 22);
        try {
            ranks.put(InetAddress.getLocalHost().getHostName(), 99);
        } catch (UnknownHostException ex) {
        }
    }
    static Node pickByOS(Topics n) {
        Node bestn = null;
        int bestrank = -1;
        for (Map.Entry<String, Node> me : ((Topics) n).children.entrySet()) {
            int g = rank(me.getKey());
            if (g > bestrank) {
                bestrank = g;
                bestn = me.getValue();
            }
        }
        return bestn;
    }
    public enum RunStatus { OK, NothingDone, Errored }
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
    @Inject ShellRunner shellRunner;
    @Inject EZTemplates templateEngine;
    protected RunStatus run(Topic t, IntConsumer background, Topics config) {
        return run(t, Coerce.toString(t.getOnce()), background, config);
    }
    protected RunStatus run(Topic t, String cmd, IntConsumer background, Topics config) {
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
    private void addEnv(Exec exec, Topics src) {
        if(src!=null) {
            addEnv(exec, src.parent); // add parents contributions first
            Node env = src.getChild("setenv");
            if(env instanceof Topics) {
                ((Topics)env).forEach(n->{
                    if(n instanceof Topic)
                        exec.setenv(n.name, templateEngine.rewrite(Coerce.toString(((Topic)n).getOnce())));
                });
            }
        }
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
    protected void addDependencies(HashSet<GGService> deps) {
        deps.add(this);
        if (dependencies != null)
            dependencies.keySet().forEach(d -> {
                if (!deps.contains(d))
                    d.addDependencies(deps);
            });
    }
    public boolean satisfiedBy(HashSet<GGService> ready) {
        return dependencies == null
                || dependencies.keySet().stream().allMatch(l -> ready.contains(l));
    }

}
