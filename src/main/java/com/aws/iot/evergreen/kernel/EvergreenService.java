/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.Log;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Singleton;

import static com.aws.iot.evergreen.util.Utils.getUltimateCause;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "Need hardcoded paths to find what OS we're on")
public class EvergreenService implements InjectionActions, Subscriber, Closeable {
    public static final String STATE_TOPIC_NAME = "_State";
    private static final Pattern DEP_PARSE = Pattern.compile(" *([^,:;& ]+)(:([^,; ]+))?[,; ]*");
    private static final Map<String, Integer> RANKS = buildRanks();

    public final Topics config;
    protected final CopyOnWriteArrayList<EvergreenService> explicitDependencies = new CopyOnWriteArrayList<>();
    private final Object dependencyReadyLock = new Object();
    private final Topic state;
    public Context context;
    protected ConcurrentHashMap<EvergreenService, State> dependencies;
    private CountDownLatch shutdownLatch = new CountDownLatch(0);
    private Throwable error;
    private Future backingTask;
    private Periodicity periodicityInformation;
    private State activeState = State.New;
    private String status;

    @SuppressWarnings("LeakingThisInConstructor")
    public EvergreenService(Topics topics) {
        this.config = topics;
        this.state = initStateTopic(topics);
    }

    public static State getState(EvergreenService evergreenService) {
        return evergreenService.getState();
    }

    static void setState(Object o, State state) {
        if (o instanceof EvergreenService) {
            ((EvergreenService) o).setState(state);
        }
    }

    /**
     * Locate an EvergreenService by name from the provided context.
     *
     * @param context context to lookup the name in
     * @param name name of the service to find
     * @return found service or null
     * @throws Throwable maybe throws?
     */
    @SuppressWarnings({"checkstyle:emptycatchblock"})
    public static EvergreenService locate(Context context, String name) throws Throwable {
        return context.getv(EvergreenService.class, name).computeIfEmpty(v -> {
            Configuration c = context.get(Configuration.class);
            Topics t = c.lookupTopics(Configuration.splitPath(name));
            assert (t != null);
            if (t.isEmpty()) {
                // No definition of this service was found in the config file.
                // weave config fragments in from elsewhere...
                Kernel k = context.get(Kernel.class);
                for (String s : k.getServiceServerURLlist()) {
                    if (t.isEmpty()) {
                        try {
                            // TODO: should probably think hard about what file extension to use
                            // TODO: allow the file to be a zip package?
                            URL u = new URL(s + name + ".evg");
                            k.read(u, false);
                            context.getLog().log(t.isEmpty() ? Log.Level.Error : Log.Level.Note, name,
                                    "Found external " + "definition", s);
                        } catch (IOException ignored) {
                        }
                    } else {
                        break;
                    }
                }
                if (t.isEmpty()) {
                    t.createLeafChild("run").dflt("echo No definition found for " + name + ";exit -1");
                }
            }
            EvergreenService ret;
            Class clazz = null;
            Node n = t.getChild("class");
            if (n != null) {
                String cn = Coerce.toString(n);
                try {
                    clazz = Class.forName(cn);
                } catch (Throwable ex) {
                    context.getLog().error("Can't find class definition", ex);
                    return errNode(context, name, "creating code-backed service from " + cn, ex);
                }
            }
            if (clazz == null) {
                Map<String, Class> si = context.getIfExists(Map.class, "service-implementors");
                if (si != null) {
                    clazz = si.get(name);
                }
            }
            if (clazz != null) {
                try {
                    Constructor ctor = clazz.getConstructor(Topics.class);
                    ret = (EvergreenService) ctor.newInstance(t);
                    if (clazz.getAnnotation(Singleton.class) != null) {
                        context.put(ret.getClass(), v);
                    }
                } catch (Throwable ex) {
                    context.getLog().error("Can't create instance of " + clazz, ex);
                    ret = errNode(context, name, "creating code-backed service from " + clazz.getSimpleName(), ex);
                }
            } else if (t.isEmpty()) {
                ret = errNode(context, name, "No matching definition in system model", null);
            } else {
                try {
                    ret = new GenericExternalService(t);
                } catch (Throwable ex) {
                    context.getLog().error("Can't create generic instance from " + Coerce.toString(t), ex);
                    ret = errNode(context, name, "Creating generic service", ex);
                }
            }
            return ret;
        });
    }

    public static EvergreenService errNode(Context context, String name, String message, Throwable ex) {
        try {
            context.getLog().error("Error locating service", name, message, ex);
            return new GenericExternalService(Topics.errorNode(context, name,
                    "Error locating service " + name + ": " + message + (ex == null ? "" : "\n\t" + ex)));
        } catch (Throwable ex1) {
            context.getLog().error(name, message, ex);
            return null;
        }
    }

    public static int rank(String s) {
        Integer i = RANKS.get(s);
        return i == null ? -1 : i;
    }

    public static Node pickByOS(Topics n) {
        Node bestn = null;
        int bestrank = -1;
        for (Map.Entry<String, Node> me : n.children.entrySet()) {
            int g = rank(me.getKey());
            if (g > bestrank) {
                bestrank = g;
                bestn = me.getValue();
            }
        }
        return bestn;
    }

    private static Map<String, Integer> buildRanks() {
        Map<String, Integer> ranks = new HashMap<>();
        // figure out what OS we're running and add applicable tags
        // The more specific a tag is, the higher its rank should be
        // TODO: a loopy set of hacks
        ranks.put("all", 0);
        ranks.put("any", 0);
        if (Files.exists(Paths.get("/bin/bash")) || Files.exists(Paths.get("/usr/bin/bash"))) {
            ranks.put("unix", 3);
            ranks.put("posix", 3);
        }
        if (Files.exists(Paths.get("/proc"))) {
            ranks.put("linux", 10);
        }
        if (Files.exists(Paths.get("/usr/bin/apt-get"))) {
            ranks.put("debian", 11);
        }
        if (Exec.isWindows) {
            ranks.put("windows", 5);
        }
        if (Files.exists(Paths.get("/usr/bin/yum"))) {
            ranks.put("fedora", 11);
        }
        String sysver = Exec.sh("uname -a").toLowerCase();
        if (sysver.contains("ubuntu")) {
            ranks.put("ubuntu", 20);
        }
        if (sysver.contains("darwin")) {
            ranks.put("macos", 20);
        }
        if (sysver.contains("raspbian")) {
            ranks.put("raspbian", 22);
        }
        if (sysver.contains("qnx")) {
            ranks.put("qnx", 22);
        }
        if (sysver.contains("cygwin")) {
            ranks.put("cygwin", 22);
        }
        if (sysver.contains("freebsd")) {
            ranks.put("freebsd", 22);
        }
        if (sysver.contains("solaris") || sysver.contains("sunos")) {
            ranks.put("solaris", 22);
        }
        try {
            ranks.put(InetAddress.getLocalHost().getHostName(), 99);
        } catch (UnknownHostException ex) {
        }

        return ranks;
    }

    private Topic initStateTopic(final Topics topics) {
        Topic state = topics.createLeafChild(STATE_TOPIC_NAME);
        state.setParentNeedsToKnow(false);
        state.setValue(Long.MAX_VALUE, State.New);
        state.validate((newStateObj, oldStateObj) -> {
            State newState = Coerce.toEnum(State.class, newStateObj);
            return newState == null ? oldStateObj : newStateObj;
        });
        state.subscribe(this);
        return state;
    }

    public State getState() {
        return (State) state.getOnce();
    }

    public void setState(State newState) {
        final State currentState = (State) this.state.getOnce();

        if (newState != currentState) {
            context.getLog().note(getName(), currentState, "=>", newState);
            // Make sure the order of setValue() invocation is same as order of global state notification
            synchronized (this.state) {
                this.state.setValue(Long.MAX_VALUE, newState);
                context.globalNotifyStateChanged(this, currentState);
            }
        }
    }

    public boolean inState(State s) {
        return s == state.getOnce();
    }

    public Topic getStateTopic() {
        return state;
    }

    public boolean isRunningInternally() {
        Future b = backingTask;
        return b != null && !b.isDone();
    }

    public boolean isPeriodic() {
        return periodicityInformation != null;
    }

    @Override // for listening to state changes
    public void published(final WhatHappened what, final Topic topic) {
        final State newState = (State) topic.getOnce();
        if (activeState == newState) {
            return;
        }
        if (activeState.isRunning() && !newState.isRunning()) { // transition from running to not running
            shutdownLatch = new CountDownLatch(1);
            // Assume that shutdown task won't be cancelled. Following states (installing/awaitingStartup) wait until
            // shutdown task complete.
            // May consider just merge shutdown() into other state's handling.
            State oldState = activeState;
            setBackingTask(() -> {
                try {
                    shutdown();
                } catch (Throwable t) {
                    if (oldState != State.Errored) {
                        errored("Failed shutting down", t);
                    } else {
                        context.getLog().error(this, "Failed shutting down", t);
                    }
                } finally {
                    shutdownLatch.countDown();
                }
                backingTask = null;
            }, getName() + "=>" + newState);

            // Since shutdown() is modeled as part of state transition, I initially tried waiting on shutdownLatch()
            // here.
            // However, this caused the global configuration publish queue being blocked.
        }

        try {
            switch (newState) {
                case Installing:
                    new Thread(() -> {
                        // wait until shutdown finished.
                        // not using setBackTask() here to avoid cancelling the ongoing shutdown task
                        try {
                            shutdownLatch.await();
                        } catch (InterruptedException e) {
                            errored("waiting for shutdown complete", e);
                            return;
                        }
                        // TODO: wait until all install dependency ready.
                        if (!errored() && getState() == State.Installing) {
                            setBackingTask(() -> {
                                try {
                                    install();
                                    if (!errored()) {
                                        setState(State.AwaitingStartup);
                                    }
                                } catch (Throwable t) {
                                    errored("Failed installing", t);
                                }
                                backingTask = null;
                            }, getName() + " => " + newState);
                        }
                    }).start();

                    break;
                case AwaitingStartup:
                    awaitingStartup();
                    new Thread(() -> {
                        // wait until shutdown finished.
                        try {
                            shutdownLatch.await();
                        } catch (InterruptedException e) {
                            errored("waiting for shutdown complete", e);
                            return;
                        }
                        if (dependencies != null) {
                            try {
                                waitForDependencyReady();
                            } catch (InterruptedException e) {
                                errored("waiting for dependency ready", e);
                            }
                        }
                        // if no other state change happened in between
                        if (!errored() && getState() == State.AwaitingStartup) {
                            setState(State.Starting);
                        }
                    }).start();

                    break;
                case Starting:
                    setBackingTask(() -> {
                        try {
                            periodicityInformation = Periodicity.of(this);
                            startup();
                            if (!errored()) {
                                setState(isPeriodic()  // Let timer do the transition to Running==null
                                        ? State.Finished : State.Running);
                            }
                        } catch (Throwable t) {
                            errored("Failed starting up", t);
                        }
                        backingTask = null;
                    }, getName() + " => " + newState);
                    break;
                case Running:
                    if (!activeState.isRunning()) {
                        setBackingTask(() -> {
                            try {
                                run();
                                // subclasses implementing run() should handle state transition;
                            } catch (Throwable t) {
                                errored("Failed starting up", t);
                            }
                            backingTask = null;
                        }, getName() + " => " + newState);
                    }
                    break;
                case Errored:
                    if (activeState != State.Errored) {
                        // already in the process of error handling
                        try {
                            handleError();
                        } catch (Throwable t) {
                            // TODO: handle the case where error happens in error recovery.
                            errored("Error handler failed", t);
                        }
                    }
                    break;
            }
        } catch (Throwable t) {
            errored("Transitioning from " + getName() + " => " + newState, t);
        }
        activeState = newState;
    }

    /**
     * @return true iff this service is in the process of transitioning from one state
     * to the state returned by getState() - setState() is "aspirational".  getState()
     * returns the state that the service aspires to be in.  inTransition() returns true
     * if that aspiration has been met.
     */
    public boolean inTransition() {
        return activeState != getState() || shutdownLatch.getCount() != 0;
    }

    private synchronized void setBackingTask(Runnable r, String db) {
        Future bt = backingTask;
        if (bt != null) {
            backingTask = null;
            if (!bt.isDone()) {
                bt.cancel(true);
            }
        }
        if (r != null) {
            backingTask = context.get(ExecutorService.class).submit(r);
        }
    }

    public void errored(String message, Throwable e) {
        e = getUltimateCause(e);
        error = e;
        errored(message, (Object) e);
    }

    public void errored(String message, Object e) {
        if (context == null) {
            if (e instanceof Throwable) {
                ((Throwable) e).printStackTrace(System.err);
            }
        } else {
            context.getLog().error(this, message, e);
        }
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
    }

    /**
     * Called when all dependencies are Running. If there are no dependencies,
     * it is called right after postInject.  The service doesn't transition to Running
     * until *after* this state is complete.  The service transitions to Running when
     * startup() completes
     */
    public void startup() {
    }

    /**
     * Called when all dependencies are Running. Transitions out of Running only happen
     * by having the run method (or some method/Thread spawned by it) explicitly set
     * the services state.  run() is executed on it's own thread, but termination of
     * that thread does not automatically trigger a state transition.  The default
     * implementation does nothing except transition immediately to Finished.
     */
    protected void run() {
        setState(State.Finished);
    }

    /**
     * Called when a running service encounters an error.
     */
    protected void handleError() {
        if (error != null) {
            context.getLog().error("Handle error", error);
            error = null;
        }
        // TODO Improve error restarts by collecting statistics on errors.  eg. If it error's often, start adding
        //  backoff waits.
        //  Maybe doing a little inspection of exceptions to be smarter.  eg. file system full messages could trigger
        //  a disk
        //  cleanup before the restart,  Or network errors could look at the network state and not restart until the
        //  network
        //  reconnects.

        switch (this.activeState) {
            case Installing:
                setState(State.Installing); // retry install
                break;
            case AwaitingStartup:
            case Starting:
            case Running:
                setState(State.AwaitingStartup); // restart
                break;
            case Finished:
                setState(State.Finished); // don't do anything
                break;
        }
    }

    /**
     * Called when the object's state leaves Running.
     * To shutdown a service, use <tt>setState(Finished)</tt>
     */
    public void shutdown() {
        Periodicity t = periodicityInformation;
        if (t != null) {
            t.shutdown();
        }
    }

    @Override
    public void close() {
        setState(State.Finished);
        // if the current task is not shutdown
        if (shutdownLatch.getCount() == 0 && backingTask != null) {
            backingTask.cancel(true);
        }
    }

    public Context getContext() {
        return context;
    }

    public void addDependency(EvergreenService dependentEvergreenService, State when) {
        if (dependencies == null) {
            dependencies = new ConcurrentHashMap<>();
        }
        context.get(Kernel.class).clearODcache();
        dependencies.put(dependentEvergreenService, when);

        dependentEvergreenService.getStateTopic().subscribe((WhatHappened what, Topic t) -> {
            if (this.getState() == State.Starting || this.getState().isRunning()) {
                // if dependency is down, restart the service and wait for dependency up
                if (!dependencyReady(dependentEvergreenService)) {
                    // TODO: if service is able to handle dependency down, don't restart.
                    context.getLog().note("Restart service because of dependency error. ", this.getName());
                    this.setState(State.AwaitingStartup);
                }
            }

            synchronized (dependencyReadyLock) {
                if (dependencyReady()) {
                    dependencyReadyLock.notifyAll();
                }
            }
        });
    }

    private boolean dependencyReady() {
        if (dependencies == null) {
            return true;
        }
        return dependencies.keySet().stream().allMatch(this::dependencyReady);
    }

    private boolean dependencyReady(EvergreenService v) {
        State state = v.getState();
        State startWhenState = dependencies.get(v);
        return (state.isHappy()) && startWhenState.preceedsOrEqual(state);
    }

    private void waitForDependencyReady() throws InterruptedException {
        synchronized (dependencyReadyLock) {
            while (!dependencyReady()) {
                dependencyReadyLock.wait();
            }
        }
    }

    public void forAllDependencies(Consumer<? super EvergreenService> f) {
        if (dependencies != null) {
            dependencies.keySet().forEach(f);
        }
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String s) {
        status = s;
    }

    public String getName() {
        return config == null ? getClass().getSimpleName() : config.getFullName();
    }

    @Override
    public void postInject() {
        addDependency(config.getChild("requires"));
    }

    public boolean addDependency(Node d) {
        boolean ret = false;
        if (d instanceof Topics) {
            d = pickByOS((Topics) d);
        }
        if (d instanceof Topic) {
            String ds = ((Topic) d).getOnce().toString();
            Matcher m = DEP_PARSE.matcher(ds);
            while (m.find()) {
                addDependency(m.group(1), m.group(3));
                ret = true;
            }
            if (!m.hitEnd()) {
                errored("bad dependency syntax", ds);
            }
        }
        return ret;
    }

    public void addDependency(String name, String startWhen) {
        if (startWhen == null) {
            startWhen = State.Running.toString();
        }
        State x = null;
        int len = startWhen.length();
        if (len > 0) {
            // do "friendly" match
            for (State s : State.values()) {
                if (startWhen.regionMatches(true, 0, s.name(), 0, len)) {
                    x = s;
                    break;
                }
            }
            if (x == null) {
                errored(startWhen + " does not match any EvergreenService state name", name);
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
            } else {
                errored("Couldn't locate", name);
            }
        } catch (Throwable ex) {
            errored("Failure adding dependency to " + this, ex);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        try {
            if (config == null) {
                sb.append("[nameless]");
            } else {
                config.appendNameTo(sb);
            }
            if (!inState(State.Running)) {
                sb.append(':').append(getState().toString());
            }
        } catch (IOException ex) {
            sb.append(ex.toString());
        }
        return sb.toString();
    }

    Node pickByOS(String name) {
        Node n = config.getChild(name);
        if (n instanceof Topics) {
            n = pickByOS((Topics) n);
        }
        return n;
    }

    protected void addDependencies(HashSet<EvergreenService> deps) {
        deps.add(this);
        if (dependencies != null) {
            dependencies.keySet().forEach(d -> {
                if (!deps.contains(d)) {
                    d.addDependencies(deps);
                }
            });
        }
    }

    public boolean satisfiedBy(HashSet<EvergreenService> ready) {
        return dependencies == null || ready.containsAll(dependencies.keySet());
    }

    public enum RunStatus {
        OK, NothingDone, Errored
    }

    public interface GlobalStateChangeListener {
        void globalServiceStateChanged(EvergreenService l, State was);
    }

}
