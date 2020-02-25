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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Singleton;

import static com.aws.iot.evergreen.util.Utils.getUltimateCause;

public class EvergreenService implements InjectionActions, Closeable {
    public static final String STATE_TOPIC_NAME_SUFFIX = "_State";
    private static final Pattern DEP_PARSE = Pattern.compile(" *([^,:;& ]+)(:([^,; ]+))?[,; ]*");

    public final Topics config;
    public Context context;

    protected final CopyOnWriteArrayList<EvergreenService> explicitDependencies = new CopyOnWriteArrayList<>();
    protected ConcurrentHashMap<EvergreenService, State> dependencies;

    private final Object dependencyReadyLock = new Object();
    private final Topic state;
    private Throwable error;
    private Future backingTask;
    private Periodicity periodicityInformation;
    private State prevState = State.NEW;
    private String status;
    private AtomicBoolean closed = new AtomicBoolean(false);

    // A state event can be a state transition event, or a desired state updated notification.
    // TODO: make class of StateEvent instead of generic object.
    private final BlockingQueue<Object> stateEventQueue = new ArrayBlockingQueue<>(1);

    // DesiredStateList is used to set desired path of state transition.
    // Eg. Start a service will need DesiredStateList to be <RUNNING>
    // ReInstall a service will set DesiredStateList to <FINISHED->NEW->RUNNING>
    private final List<State> desiredStateList = new CopyOnWriteArrayList<>();

    private static final Set<State> ALLOWED_STATES_FOR_REPORTING = new HashSet<>(Arrays.asList(
            State.RUNNING, State.ERRORED, State.FINISHED));

    @SuppressWarnings("LeakingThisInConstructor")
    public EvergreenService(Topics topics) {
        this.config = topics;
        this.state = initStateTopic(topics);
    }

    public State getState() {
        return (State) state.getOnce();
    }

    private void setState(State newState) {
        final State currentState = getState();

        if (newState.equals(currentState)) {
            return;
        }

        // TODO: Add validation
        context.getLog().note(getName(), currentState, "=>", newState);
        prevState = currentState;
        this.state.setValue(newState);
        context.globalNotifyStateChanged(this, currentState);
    }

    /**
     * public API for service to report state. Allowed state are RUNNING, FINISHED, ERRORED.
     * @param newState
     * @return
     */
    public synchronized void reportState(State newState) {
        context.getLog().note(getName(), "reporting state", newState);
        if (!ALLOWED_STATES_FOR_REPORTING.contains(newState)) {
            context.getLog().error("invalid report state: " + newState);
        }
        // TODO: Add more validations

        if (getState().equals(State.INSTALLED) && newState.equals(State.FINISHED)) {
            // if a service doesn't have any run logic, request stop on service to clean up DesiredStateList
            requestStop();
        }

        enqueueStateEvent(newState);
    }

    private Optional<State> getReportState() {
        Object top = stateEventQueue.poll();
        if (top instanceof State) {
            return Optional.of((State) top);
        }
        return Optional.empty();
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
        Topic state = topics.createLeafChild(STATE_TOPIC_NAME_SUFFIX);
        state.setParentNeedsToKnow(false);
        state.setValue(State.NEW);
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

    private Optional<State> peekOrRemoveFirstDesiredState(State activeState) {
        if (desiredStateList.isEmpty()) {
            return Optional.empty();
        }
        State first = desiredStateList.get(0);
        if (first == activeState) {
            desiredStateList.remove(first);
            // ignore remove() return value as it's possible that desiredStateList update
        }
        return Optional.ofNullable(first);
    }

    // Set desiredStateList and override existing desiredStateList.
    // Expect to have multi-thread access
    private synchronized void setDesiredState(State... state) {
        synchronized (desiredStateList) {
            List<State> newStateList = Arrays.asList(state);
            if (newStateList.equals(desiredStateList)) {
                return;
            }
            desiredStateList.clear();
            desiredStateList.addAll(newStateList);
            // try insert to the queue, if queue full doesn't block.
            enqueueStateEvent("DesiredStateUpdated");
        }
    }

    @SuppressFBWarnings(value = "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    private synchronized void enqueueStateEvent(Object event) {
        if (event instanceof State) {
            // override existing reportState
            stateEventQueue.clear();
            stateEventQueue.offer(event);
        } else {
            stateEventQueue.offer(event);

            // Ignore returned value of offer().
            // If enqueue isn't successful, the event queue has contents and there is no need to send another
            // trigger to process state transition.
        }
    }

    /**
     * Start Service.
     */
    public void requestStart() {
        setDesiredState(State.RUNNING);
    }

    /**
     * Stop Service.
     */
    public void requestStop() {
        setDesiredState(State.FINISHED);
    }

    /**
     * Restart Service.
     */
    public void requestRestart() {
        setDesiredState(State.FINISHED, State.RUNNING);
    }

    /**
     * ReInstall Service.
     */
    public void requestReinstall() {
        setDesiredState(State.FINISHED, State.NEW, State.RUNNING);
    }

    private void startStateTransition() throws InterruptedException {
        periodicityInformation = Periodicity.of(this);
        while (!closed.get()) {
            Optional<State> desiredState;

            State current = getState();
            context.getLog().note("Processing state", getName(), getState());

            // if already in desired state, remove the head of desired state list.
            desiredState = peekOrRemoveFirstDesiredState(current);
            while (desiredState.isPresent() && desiredState.get().equals(current)) {
                desiredState = peekOrRemoveFirstDesiredState(current);
            }

            switch (current) {
                case BROKEN:
                    context.getLog().significant(getName(), "BROKEN");
                    return;
                case NEW:
                    // if no desired state is set, don't do anything.
                    if (!desiredState.isPresent()) {
                        break;
                    }
                    CountDownLatch installLatch = new CountDownLatch(1);
                    setBackingTask(() -> {
                        try {
                            install();
                        } catch (Throwable t) {
                            reportState(State.ERRORED);
                            getContext().getLog().error(getName(), "Error in install", t);
                        } finally {
                            installLatch.countDown();
                        }
                    }, "install");

                    // TODO: Configurable timeout logic.
                    boolean ok = installLatch.await(120, TimeUnit.SECONDS);
                    State reportState = getReportState().orElse(null);
                    if (State.ERRORED.equals(reportState) || !ok) {
                        setState(State.ERRORED);
                    } else {
                        setState(State.INSTALLED);
                    }
                    continue;
                case INSTALLED:
                    if (!desiredState.isPresent()) {
                        break;
                    }

                    switch (desiredState.get()) {
                        case FINISHED:
                            stopBackingTask();
                            setState(State.FINISHED);
                            continue;
                        case RUNNING:
                            setBackingTask(() -> {
                                try {
                                    waitForDependencyReady();
                                    context.getLog().note(getName(), "starting");
                                } catch (InterruptedException e) {
                                    context.getLog().note(getName(), "Get interrupted when waiting for dependency ready");
                                    return;
                                }

                                try {
                                    startup();// TODO: rename to  initiateStartup. Service need to report state to RUNNING.
                                } catch (Throwable t) {
                                    reportState(State.ERRORED);
                                    getContext().getLog().error(getName(), "Error in running", t);
                                }
                            }, "start");

                            break;
                        default:
                            context.getLog().error("Unexpected desired state", desiredState);
                            // not allowed for NEW, STOPPING, ERRORED, BROKEN
                            break;
                    }
                    break;
                case RUNNING:
                    if (!desiredState.isPresent()) {
                        break;
                    }

                    setState(State.STOPPING);
                    continue;
                case STOPPING:
                    // doesn't handle desiredState in STOPPING.
                    // Not use setBackingTask because it will cancel the existing task.
                    CountDownLatch stopping = new CountDownLatch(1);
                    new Thread(() -> {
                        try {
                            shutdown();
                        } catch (Throwable t) {
                            getContext().getLog().error(getName(), "Error in shutting down", t);
                            reportState(State.ERRORED);
                        } finally {
                            stopping.countDown();
                        }

                    }).start();

                    boolean stopSucceed = stopping.await(30, TimeUnit.SECONDS);
                    if (State.ERRORED.equals(getReportState().orElse(null)) || !stopSucceed) {
                        setState(State.ERRORED);
                        continue;
                    } else {
                        setState(State.FINISHED);
                        continue;
                    }

                case FINISHED:
                    if (!desiredState.isPresent()) {
                        break;
                    }

                    context.getLog().note(getName(), getState(), "desiredState", desiredState);
                    switch (desiredState.get()) {
                        case NEW:
                        case INSTALLED:
                            setState(State.NEW);
                            continue;
                        case RUNNING:
                            setState(State.INSTALLED);
                            continue;
                        default:
                            context.getLog().error("Unexpected desired state", desiredState);
                            // not allowed to set desired state to STOPPING, ERRORED, BROKEN
                    }
                    break;
                case ERRORED:
                    handleError();
                    //TODO: Set service to broken state if error happens too often
                    if (!desiredState.isPresent()) {
                        requestStart();
                    }
                    switch (prevState) {
                        case RUNNING:
                            setState(State.STOPPING);
                            continue;
                        case NEW: // error in installing.
                            setState(State.NEW);
                            continue;
                        case INSTALLED: // error in starting
                            setState(State.INSTALLED);
                            continue;
                        case STOPPING:
                            // not handled;
                            setState(State.FINISHED);
                            continue;
                        default:
                            context.getLog().error("Unexpected previous state", prevState);
                            setState(State.FINISHED);
                            // not allowed
                            continue;
                    }
                default:
                    context.getLog().error("Unrecognized state", getState());
                    break;
            }

            // blocking on event queue.
            // The state event can either be a report state transition event or a desired state updated event.
            // TODO: check if it's possible to move this blocking logic to the beginning of while loop.
            Object stateEvent = stateEventQueue.take();
            if (stateEvent instanceof State) {
                State toState = (State) stateEvent;
                context.getLog().note(getName(), "Get reported state", toState);
                setState(toState);
            }
        }
    }

    /**
     * Custom handler to handle error.
     */
    public void handleError() {
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
        reportState(State.ERRORED);
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
     * Called when all dependencies are RUNNING. If there are no dependencies,
     * it is called right after postInject.  The service doesn't transition to RUNNING
     * until *after* this state is complete.
     */
    protected void startup() {
        reportState(State.RUNNING);
    }

    @Deprecated
    public void run() {
        reportState(State.FINISHED);
    }

    /**
     * Called when the object's state leaves RUNNING.
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

        closed.set(true);
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
            if (this.getState() == State.INSTALLED || this.getState() == State.RUNNING) {
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
        //TODO: Use better threadPool mechanism
        new Thread(() -> {
            while (!closed.get()) {
                try {
                    startStateTransition();
                    return;
                } catch (Throwable e) {
                    context.getLog().error("Error in handling state transition", getName(), getState(), e);
                    context.getLog().note("Restart handling state transition", getName());
                }
            }
        }).start();

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
                errored(startWhen + " does not match any EvergreenService state name", name);
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
