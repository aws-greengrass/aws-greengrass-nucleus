/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Node;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Singleton;

import static com.aws.iot.evergreen.util.Utils.getUltimateCause;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Need hardcoded paths to find what OS we're on")
public class EvergreenService implements InjectionActions, Closeable {
    public static final String STATE_TOPIC_NAME = "_State";
    private static final Pattern DEP_PARSE = Pattern.compile(" *([^,:;& ]+)(:([^,; ]+))?[,; ]*");
    private static final Map<String, Integer> RANKS = buildRanks();

    public final Topics config;
    public Context context;

    protected final CopyOnWriteArrayList<EvergreenService> explicitDependencies = new CopyOnWriteArrayList<>();
    protected ConcurrentHashMap<EvergreenService, State> dependencies;

    private final Object dependencyReadyLock = new Object();
    private final Topic stateTopic;
    private final CopyOnWriteArrayList<State> desiredStatesSequence = new CopyOnWriteArrayList<>();

    private Throwable error;
    private Periodicity periodicityInformation;

    public State getActiveState() {
        return activeState;
    }

    private State activeState = State.NEW;
    private String status;

    @SuppressWarnings("LeakingThisInConstructor")
    public EvergreenService(Topics topics) {
        this.config = topics;
        this.stateTopic = initStateTopic(topics);
    }

    private Topic initStateTopic(final Topics topics) {
        Topic state = topics.createLeafChild(STATE_TOPIC_NAME);
        state.setParentNeedsToKnow(false);
        state.setValue(Long.MAX_VALUE, State.NEW);
        state.validate((newStateObj, oldStateObj) -> {
            State newState = Coerce.toEnum(State.class, newStateObj);
            return newState == null ? oldStateObj : newStateObj;
        });
        return state;
    }

    public static State getState(EvergreenService evergreenService) {
        return evergreenService.getActiveState();
    }

    static void setState(Object o, State state) {
        if (o instanceof EvergreenService) {
            ((EvergreenService) o).broadcastStateChange(state);
        }
    }

    public void addDesiredState(State desiredState) {
        if (desiredStatesSequence.size() > 5) {
            // TODO Exception refinement
            throw new RuntimeException("Can't take more than 5 state changes.");
        }

        desiredStatesSequence.add(desiredState);
    }

    public void requestRestart() {
        desiredStatesSequence.addAll(Arrays.asList(State.FINISHED, State.RUNNING));
    }


    protected void updateActiveState(State newState) {
        State oldState = activeState;
        activeState = newState;
        context.getLog().significant(this, "Transited", oldState, "=>", newState);
        broadcastStateChange(newState);
    }

    private void startLifeCycleFsm() {
        while (true) {
            if (!desiredStatesSequence.isEmpty()) {

                State desiredState = desiredStatesSequence.remove(0);

                context.getLog().significant(this, "Transitioning", activeState, "=>", desiredState);

                if (desiredState.equals(activeState)) {
                    continue;
                }

                if (activeState.equals(State.ERRORED)) {
                    // Leaving error state. Reset error to null.
                    error = null;
                }

                switch (desiredState) {

                    case INSTALLED:
                        switch (activeState) {
                            case NEW:
                            case ERRORED:
                            case FINISHED:
                                // new -> installed
                                // finished -> installed
                                // errored -> installed
                                context.getLog().note(this, "Installing");

                                try {
                                    install();
                                    updateActiveState(State.INSTALLED);
                                    desiredStatesSequence.add(State.RUNNING);
                                } catch (Throwable t) {
                                    errored("Failed when installing", t);
                                }
                                break;
                            default:
                                context.getLog().error(this, "Invalid State Transition");
                                break;
                        }
                        break;

                    case RUNNING:
                        switch (activeState) {
                            case NEW:
                            case FINISHED:
                                // New -> Running
                                // Finished -> Running
                                desiredStatesSequence.add(State.INSTALLED);
                                break;

                            case ERRORED:
                            case INSTALLED:
                                // Installed -> Running
                                // Errored -> Running

                                // wait for dependency
                                if (dependencies != null) {
                                    try {
                                        context.getLog().note(this,"Waiting for dependency");
                                        waitForDependencyReady();
                                        context.getLog().note(this,"Dependency is ready");

                                    } catch (InterruptedException e) {
                                        errored("Interrupted while waiting for dependency ready", e);
                                    }
                                }

                                // starting up
                                try {
                                    context.getLog().note(this, "Calling starting up");
                                    startup();
                                    context.getLog().note(this, "Called starting up");
                                } catch (Throwable t) {
                                    errored("Failed when starting up", t);
                                }

                                // run
                                try {
                                    context.getLog().note(this, "Calling run");
                                    run();
                                    context.getLog().note(this, "Called run");
                                } catch (Throwable t) {
                                    errored("Failed when running", t);
                                }

                                if (!errored()) {
                                    updateActiveState(State.RUNNING);

                                    if (isPeriodic()) {
                                        desiredStatesSequence.add(State.FINISHED);
                                    }
                                }
                                break;
                            default:
                                context.getLog().error(this, "Invalid State Transition");
                                break;
                        }
                        break;
                    case STOPPING:
                        if (activeState == State.RUNNING) {
                            // Running -> Stopping
                            updateActiveState(State.STOPPING);

                            try {
                                context.getLog().note(this, "Calling shutdown");
                                shutdown();
                                context.getLog().note(this, "Called shutdown");

                            } catch (Throwable t) {
                                errored("Failed when shutting down", t);
                            }
                        } else {
                            // Other states can directly go to finished
                            desiredStatesSequence.add(State.FINISHED);
                        }
                        break;

                    case FINISHED:
                        if (activeState == State.RUNNING) {
                            // Running -> Finished
                            // Go through stopping first
                            desiredStatesSequence.addAll(0, Arrays.asList(State.STOPPING, State.FINISHED));
                        } else {
                            context.getLog().note(this, "Update active state to finished");
                            updateActiveState(State.FINISHED);
                        }
                        break;

                    case ERRORED:
                        context.getLog().error(this, "Errored");
                        desiredStatesSequence.clear();

                        //  error recovery
                        switch (activeState) {
                            case NEW:
                                // New -> Errored
                                // Failed when New -> Install
                                desiredStatesSequence.add(State.INSTALLED);
                                break;
//
                            case INSTALLED:
//                              Installed -> Errored
                                // Failed when Installed -> Running
                                desiredStatesSequence.add(State.RUNNING);
                                break;

                            case RUNNING:
                                // Running -> Errored
                                requestRestart();
                                break;

                            case STOPPING:
                                desiredStatesSequence.add(State.STOPPING);
                                break;

                            default:
                                context.getLog().note(this, "Unhandled error.");
                                break;
                        }

                        updateActiveState(State.ERRORED);
                        break;
                    case BROKEN:
                        break;

                    default:
                        break;
                }

            }
        }

    }

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
                            context.getLog()
                                    .log(t.isEmpty() ? Log.Level.Error : Log.Level.Note, name, "Found external " + "definition", s);
                        } catch (IOException ex) {
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
            return new GenericExternalService(Topics
                    .errorNode(context, name, "Error locating service " + name + ": " + message + (ex == null ? "" : "\n\t" + ex)));
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

    public void broadcastStateChange(State newState) {
        final State prevState = (State) this.stateTopic.getOnce();

        if (newState != prevState) {
            context.getLog().note(getName(), "Broadcasting changes: " + prevState + " => " + newState);
            // Make sure the order of setValue() invocation is same as order of global stateTopic notification
            synchronized (this.stateTopic) {
                this.stateTopic.setValue(Long.MAX_VALUE, newState);
                context.globalNotifyStateChanged(this, prevState, newState);
            }
        }
    }

    public boolean inState(State state) {
        return state == activeState;
    }

    public Topic getStateTopic() {
        return stateTopic;
    }

    public boolean isPeriodic() {
        return periodicityInformation != null;
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
        desiredStatesSequence.add(0, State.ERRORED);
    }

    public boolean errored() {
        return !getActiveState().isHappy() || error != null;
    }

    /**
     * Called when this service is known to be needed to make sure that required
     * additional software is installed.
     */
    protected void install() {
    }

    /**
     * Called when this service is known to be needed, and is AWAITING_STARTUP.
     * This is a good place to do any preconfiguration.  It is seperate from "install"
     * because there are situations (like factory preflight setup) where there's a
     * certain amount of setup to be done, but we're not actually going to start the app.
     */
    protected void awaitingStartup() {
    }

    /**
     * Called when all dependencies are RUNNING. If there are no dependencies,
     * it is called right after postInject.  The service doesn't transition to RUNNING
     * until *after* this stateTopic is complete.  The service transitions to RUNNING when
     * startup() completes
     */
    public void startup() {
    }

    /**
     * Called when all dependencies are RUNNING. Transitions out of RUNNING only happen
     * by having the run method (or some method/Thread spawned by it) explicitly set
     * the services stateTopic.  run() is executed on it's own thread, but termination of
     * that thread does not automatically trigger a stateTopic transition.  The default
     * implementation does nothing except transition immediately to FINISHED.
     */
    protected void run() {
        addDesiredState(State.FINISHED);
    }

    /**
     * Called when the object's stateTopic leaves RUNNING.
     * To shutdown a service, use <tt>broadcastStateChange(FINISHED)</dd>
     */
    public void shutdown() throws IOException {
        Periodicity t = periodicityInformation;
        if (t != null) {
            t.shutdown();
        }
    }

    protected void handleError() {
    }

    @Override
    public void close() {
        if (activeState != State.STOPPING && activeState != State.FINISHED) {
            addDesiredState(State.FINISHED);
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

        dependentEvergreenService.getStateTopic().subscribe((WhatHappened what, Topic stateTopic) -> {
            State newState = (State) stateTopic.getOnce();

            if (newState.equals(State.ERRORED)) {
                context.getLog().note(this, "Restarting service because of dependency error.");
                requestRestart();
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
        State activeState = v.getActiveState();
        State startWhenState = dependencies.get(v);
        return (activeState.isHappy()) && startWhenState.preceedsOrEqual(activeState);
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

        if (periodicityInformation == null) {
            this.periodicityInformation = Periodicity.of(this);
        }

        // start lifeCycle state machine
        new Thread(this::startLifeCycleFsm).start();
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
            startWhen = State.RUNNING.toString();
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
                errored(startWhen + " does not match any EvergreenService stateTopic name", name);
            }
        }
        addDependency(name, x == null ? State.RUNNING : x);
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
            if (!inState(State.RUNNING)) {
                sb.append(':').append(getActiveState().toString());
            }
            sb.append(':').append(desiredStatesSequence);

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
        void globalServiceStateChanged(EvergreenService service, State prevState, State activeState);
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

}
