/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen;

import com.aws.iot.evergreen.config.*;
import com.aws.iot.evergreen.dependency.*;
import com.aws.iot.evergreen.util.*;
import static com.aws.iot.evergreen.util.Utils.getUltimateCause;
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


public class EvergreenService implements InjectionActions, Subscriber, Closeable {
    private final Topic state;
    private Throwable error;
    protected ConcurrentHashMap<EvergreenService, State> dependencies;
    private Future backingTask;
    public Context context;
    public static State getState(EvergreenService o) {
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
        State was = (State) state.getOnce();
        if(s!=was) {
            state.setValue(Long.MAX_VALUE, s);
            context.globalNotifyStateChanged(this, was);
        }
    }
    private State activeState = State.New;
    @Override // for listening to state changes
    public void published(final WhatHappened what, final Topic topic) {
        final State newState = (State) topic.getOnce();
        System.out.println(getName() + ": " + activeState + " -> " + newState);
        if(activeState.isRunning() && !newState.isRunning()) { // transition from running to not running
            setBackingTask(() -> {
                try {
                    shutdown();
                } catch (Throwable t) {
                    errored("Failed shutting down", t);
                }
            }, getName() + "=>" + newState);
        }
        try {
            switch(newState) {
                case Installing:
                    setBackingTask(() -> {
                        try {
                            install();
                            setState(State.AwaitingStartup);
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
                    if(activeState != State.Unstable) {
                        recheckOthersDependencies();
                        setBackingTask(() -> {
                            try {
                                run();
                                // subclasses implementing run() should handle state transition;
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
        if (o instanceof EvergreenService)
            ((EvergreenService) o).setState(st);
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
        if(!hasDependencies() && !errored()) setState(State.Starting);
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
        setState(State.Finished);
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
    public void addDependency(EvergreenService v, State when) {
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
    public void forAllDependencies(Consumer<? super EvergreenService> f) {
        if(dependencies!=null) dependencies.keySet().forEach(f);
    }
    private void recheckOthersDependencies() {
        if (context != null) {
            final AtomicBoolean changed = new AtomicBoolean(true);
            while (changed.get()) {
                changed.set(false);
                context.forEach(v -> {
                    Object vv= v.value;
                    if(vv instanceof EvergreenService) {
                        EvergreenService l = (EvergreenService) vv;
                        if (l.inState(State.AwaitingStartup)) {
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
    public interface GlobalStateChangeListener {
        void globalServiceStateChanged(EvergreenService l, State was);
    }
    public final Topics config;
    protected final CopyOnWriteArrayList<EvergreenService> explicitDependencies = new CopyOnWriteArrayList<>();
    @SuppressWarnings("LeakingThisInConstructor")
    public EvergreenService(Topics c) {
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
                    errored("does not match any EvergreenService state", startWhen);
            }
        }
        addDependency(name, x == null ? State.Running : x);
    }
    public void addDependency(String name, State startWhen) {
        try {
            EvergreenService d = locate(context, name);
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
            if (!inState(State.Running))
                sb.append(':').append(getState().toString());
        } catch (IOException ex) {
            sb.append(ex.toString());
        }
        return sb.toString();
    }
    public static EvergreenService locate(Context context, String name) throws Throwable {
        return context.getv(EvergreenService.class, name).computeIfEmpty(v->{
            Configuration c = context.get(Configuration.class);
            Topics t = c.lookupTopics(Configuration.splitPath(name));
            assert(t!=null);
            EvergreenService ret;
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
                    ret = (EvergreenService) ctor.newInstance(t);
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
    public static EvergreenService errNode(Context context, String name, String message, Throwable ex) {
        try {
            context.get(Log.class).error("Error locating service",name,message,ex);
            EvergreenService service = new GenericExternalService(Topics.errorNode(context, name,
                    "Error locating service " + name + ": " + message
                            + (ex == null ? "" : "\n\t" + ex)));
            return service;
        } catch (Throwable ex1) {
            context.get(Log.class).error(name,message,ex);
            return null;
        }
    }

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

    protected void addDependencies(HashSet<EvergreenService> deps) {
        deps.add(this);
        if (dependencies != null)
            dependencies.keySet().forEach(d -> {
                if (!deps.contains(d))
                    d.addDependencies(deps);
            });
    }
    public boolean satisfiedBy(HashSet<EvergreenService> ready) {
        return dependencies == null
                || dependencies.keySet().stream().allMatch(l -> ready.contains(l));
    }


}
