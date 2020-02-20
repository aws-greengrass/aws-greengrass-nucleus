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
import com.aws.iot.evergreen.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

public class EvergreenService implements InjectionActions, Closeable {
    public static final String STATE_TOPIC_NAME = "_State";
    private static final Pattern DEP_PARSE = Pattern.compile(" *([^,:;& ]+)(:([^,; ]+))?[,; ]*");

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
    private State prevState = State.New;
    private String status;

    private final List<State> desiredStateList = new ArrayList<>(3);

    //Used to notify when state has changed Or a desiredState is set.
    private final Object stateChangeEvent = new Object();

    @SuppressWarnings("LeakingThisInConstructor")
    public EvergreenService(Topics topics) {
        this.config = topics;
        this.state = initStateTopic(topics);
    }

    /**
     * Start the Lifecycle in a separate thread.
     */
    public void startLifecycle() {
        new Thread(() -> {
            try {
                startStateTransition();
            } catch (Throwable e) {
                context.getLog().error("Error in state transition", e);
                System.err.println("Error in state transition");
                e.printStackTrace(System.err);
                context.getLog().note("restarting service", getName());
                startLifecycle();
            }
        }).start();
    }

    public State getState() {
        return (State) state.getOnce();
    }

    /**
     * Set the state of the service. Should only be called by service or through IPC.
     * @param newState
     */
    private void setState(State newState) {
        final State currentState = getState();

        // TODO: Add validation
        if (!newState.equals(currentState)) {
            context.getLog().note(getName(), currentState, "=>", newState);
            prevState = currentState;
            // Make sure the order of setValue() invocation is same as order of global state notification
            synchronized (this.state) {
                this.state.setValue(newState);
                context.globalNotifyStateChanged(this, currentState);
                synchronized (this.stateChangeEvent) {
                    stateChangeEvent.notifyAll();
                }
            }
        }
    }

    public void reportState(State newState) {
        if (newState.equals(State.Installed) || newState.equals(State.Broken) || newState.equals(State.Finished)) {
            throw new IllegalArgumentException("Invalid state: " + newState);
        }
        setState(newState);
    }

    /**
     * Locate an EvergreenService by name from the provided context.
     *
     * @param context context to lookup the name in
     * @param name    name of the service to find
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

    private Topic initStateTopic(final Topics topics) {
        Topic state = topics.createLeafChild(STATE_TOPIC_NAME);
        state.setParentNeedsToKnow(false);
        state.setValue(State.New);
        state.validate((newStateObj, oldStateObj) -> {
            State newState = Coerce.toEnum(State.class, newStateObj);
            return newState == null ? oldStateObj : newStateObj;
        });

        return state;
    }



    public boolean inState(State s) {
        return s == state.getOnce();
    }

    public Topic getStateTopic() {
        return state;
    }

    public boolean isPeriodic() {
        return periodicityInformation != null;
    }

    private State peekOrRemoveFirstDesiredState(State activeState) {
        synchronized (desiredStateList) {
            if (desiredStateList.isEmpty()) {
                return null;
            }
            State first = desiredStateList.get(0);
            if (first == activeState) {
                return desiredStateList.remove(0);
            } else {
                return first;
            }
        }
    }

    private void setDesiredState(State... state) {
        synchronized (desiredStateList) {
            List<State> newStateList = Arrays.asList(state);
            if (newStateList.equals(desiredStateList)) {
                return;
            }
            desiredStateList.clear();
            desiredStateList.addAll(newStateList);
            synchronized (stateChangeEvent) {
                stateChangeEvent.notifyAll();
            }
        }
    }

    /**
     * Start Service.
     * @return successful
     */
    public boolean requestStart() {
        setDesiredState(State.Running);
        return true;
    }

    /**
     * Stop Service.
     * @return successful
     */
    public boolean requestStop() {
        setDesiredState(State.Finished);
        return true;
    }

    /**
     * Restart Service.
     * @return successful
     */
    public boolean requestRestart() {
        setDesiredState(State.Finished, State.Running);
        return true;
    }

    /**
     * ReInstall Service.
     * @return successful
     */
    public boolean requestReinstall() {
        setDesiredState(State.Finished, State.New, State.Running);
        return true;
    }

    /**
     * Error out a service.
     */
    void errorService() {
        setState(State.Errored);
    }

    private void waitOnStateChangeEvent(State currentState) throws InterruptedException {
        synchronized (stateChangeEvent) {
            while (desiredStateList.isEmpty() && getState() == currentState) {
                stateChangeEvent.wait();
            }
        }
    }

    private void startStateTransition() throws InterruptedException {
        periodicityInformation = Periodicity.of(this);
        while (true) {
            State desiredState = null;
            switch (this.getState()) {
                case Broken:
                    context.getLog().significant(getName(), "Broken");
                    return;
                case New:
                    desiredState = peekOrRemoveFirstDesiredState(State.New);
                    if (desiredState == null || desiredState == State.New) {
                        waitOnStateChangeEvent(State.New);
                        continue;
                    }
                    // TODO: Add install() to setBackTask with timeout logic.
                    try {
                        install();
                    } catch (Throwable t) {
                        errored("Error in install", t);
                    }
                    if (getState() != State.Errored) {
                        setState(State.Installed);
                    }
                    break;
                case Installed:
                    desiredState = peekOrRemoveFirstDesiredState(State.Installed);
                    if (desiredState == null || desiredState == State.Installed) {
                        waitOnStateChangeEvent(State.Installed);
                        continue;
                    }

                    switch (desiredState) {
                        case Finished:
                            stopBackingTask();
                            setState(State.Finished);
                            break;
                        case Running:
                            //TODO: use backing task
                            setBackingTask(() -> {
                                try {
                                    waitForDependencyReady();
                                    context.getLog().error(getName(), "starting");
                                    startup();// TODO: rename to  initiateStartup. Service need to report state to Running.
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }, "start");

                            synchronized (stateChangeEvent) {
                                if (getState() == State.Installed) {
                                    stateChangeEvent.wait();
                                }
                            }

                            break;
                        default:
                            context.getLog().error("Unexpected desired state", desiredState);
                            // not allowed for New, Stopping, Errored, Broken
                            break;
                    }
                    break;
                case Running:
                    // TODO: Start health check here.
                    desiredState = peekOrRemoveFirstDesiredState(State.Running);
                    if (desiredState == null || desiredState == State.Running) {
                        waitOnStateChangeEvent(State.Running);
                        continue;
                    }
                    setState(State.Stopping);
                    break;
                case Stopping:
                    // doesn't handle desiredState in Stopping.
                    shutdown();
                    if (this.getState() != State.Errored) {
                        setState(State.Finished);
                    }
                    break;
                case Finished:
                    desiredState = peekOrRemoveFirstDesiredState(State.Finished);
                    if (desiredState == null || desiredState == State.Finished) {
                        waitOnStateChangeEvent(State.Finished);
                        continue;
                    }
                    context.getLog().note(getName(), getState(), "desiredState", desiredState);
                    switch (desiredState) {
                        case New:
                        case Installed:
                            setState(State.New);
                            break;
                        case Running:
                            setState(State.Installed);
                            break;
                        default:
                            context.getLog().error("Unexpected desired state", desiredState);
                            // not allowed to set desired state to Stopping, Errored, Broken
                    }
                    break;
                case Errored:
                    handleError();
                    //TODO: Set service to broken state if error happens too often
                    switch (prevState) {
                        case Running:
                            setState(State.Stopping);
                            break;
                        case New: // error in installing.
                            setState(State.New);
                            break;
                        case Installed: // error in starting
                            setState(State.Installed);
                            break;
                        case Stopping:
                            // not handled;
                            setState(State.Finished);
                            break;
                        default:
                            context.getLog().error("Unexpected previous state", prevState);
                            setState(State.Finished);
                            // not allowed
                            break;
                    }
                    if (desiredStateList.isEmpty()) {
                        requestStart();
                    }
                    break;
                default:
                    context.getLog().error("Unrecognized state", getState());
                    break;
            }
        }
    }

    /**
     * Custom handler to handle error.
     */
    public void handleError() {
    }

    /**
     * @return true iff this service is in the process of transitioning from one state
     * to the state returned by getState() - setState() is "aspirational".  getState()
     * returns the state that the service aspires to be in.  inTransition() returns true
     * if that aspiration has been met.
     */
    public boolean inTransition() {
        return prevState != getState() || shutdownLatch.getCount() != 0;
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

    private void stopBackingTask() {
        setBackingTask(null, null);
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
     * until *after* this state is complete.
     */
    protected void startup() {
        setState(State.Running);
    }

    @Deprecated
    public void run() {
        setState(State.Finished);
    }

    /**
     * Called when the object's state leaves Running.
     * To shutdown a service, use <tt>setState(Finished)</tt>
     */
    protected void shutdown() {
        Periodicity t = periodicityInformation;
        if (t != null) {
            t.shutdown();
        }
    }

    @Override
    public void close() {
        requestStop();
        Periodicity t = periodicityInformation;
        if (t != null) {
            t.shutdown();
        }

        // if the current task is not shutdown
        if (shutdownLatch.getCount() == 0 && backingTask != null) {
            backingTask.cancel(true);
        }
    }

    public Context getContext() {
        return context;
    }

    /**
     * Add dependency
     *
     * @param dependentEvergreenService
     * @param when
     */
    public void addDependency(EvergreenService dependentEvergreenService, State when) {
        if (dependencies == null) {
            dependencies = new ConcurrentHashMap<>();
        }
        context.get(Kernel.class).clearODcache();
        dependencies.put(dependentEvergreenService, when);

        dependentEvergreenService.getStateTopic().subscribe((WhatHappened what, Topic t) -> {
            if (this.getState() == State.Installed || this.getState() == State.Running) {
                if (!dependencyReady(dependentEvergreenService)) {
                    this.requestRestart();
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
                context.getLog().note(getName(), "waiting for dependency ready");
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
        Node d = config.getChild("requires");
        if (d instanceof Topic) {
            String ds = ((Topic) d).getOnce().toString();
            Matcher m = DEP_PARSE.matcher(ds);
            while (m.find()) {
                addDependency(m.group(1), m.group(3));
            }
            if (!m.hitEnd()) {
                errored("bad dependency syntax", ds);
            }
        } else if (d == null) {
            return;
        } else {
            String errMsg = String.format("Unrecognized dependency configuration, config content: %s", d.toString());
            context.getLog().error(getName(), errMsg);
            // TODO: invalidate the config file
        }
    }

    private void addDependency(String name, String startWhen) {
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
