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
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Pair;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
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
import java.util.stream.Collectors;
import javax.inject.Singleton;

import static com.aws.iot.evergreen.util.Utils.getUltimateCause;

public class EvergreenService implements InjectionActions, Closeable {
    public static final String STATE_TOPIC_NAME = "_State";
    private static final Pattern DEP_PARSE = Pattern.compile(" *([^,:;& ]+)(:([^,; ]+))?[,; ]*");

    public final Topics config;
    public Context context;
    // Services that this service depend on
    protected final ConcurrentHashMap<EvergreenService, State> dependencies = new ConcurrentHashMap<>();
    // Services that depend on this service
    protected final List<EvergreenService> dependers = new CopyOnWriteArrayList<>();
    private final Object dependencyReadyLock = new Object();
    private final Object dependersExitedLock = new Object();
    private final Topic state;
    private Throwable error;
    private Future backingTask;
    private Periodicity periodicityInformation;
    private State prevState = State.NEW;
    private String status;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // A state event can be a state transition event, or a desired state updated notification.
    // TODO: make class of StateEvent instead of generic object.
    private final BlockingQueue<Object> stateEventQueue = new ArrayBlockingQueue<>(1);

    // DesiredStateList is used to set desired path of state transition.
    // Eg. Start a service will need DesiredStateList to be <RUNNING>
    // ReInstall a service will set DesiredStateList to <FINISHED->NEW->RUNNING>
    private final List<State> desiredStateList = new CopyOnWriteArrayList<>();

    private static final Set<State> ALLOWED_STATES_FOR_REPORTING =
            new HashSet<>(Arrays.asList(State.RUNNING, State.ERRORED, State.FINISHED));
    private final Topic requiresTopic;

    // Static logger instance for static methods
    private static final Logger staticLogger = LogManager.getLogger(EvergreenService.class);
    // Service logger instance
    protected final Logger logger;

    /**
     * Constructor for EvergreenService.
     *
     * @param topics root Configuration topic for this service
     */
    public EvergreenService(Topics topics) {
        this.config = topics;
        this.context = topics.context;
        this.logger = LogManager.getLogger(getName());
        logger.addDefaultKeyValue("serviceName", getName());
        this.state = initStateTopic(topics);

        this.requiresTopic = topics.createLeafChild("requires").dflt("");
        this.requiresTopic.setParentNeedsToKnow(false);
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
        logger.atInfo().setEventType("service-set-state").addKeyValue("currentState", currentState)
                .addKeyValue("newState", newState).log();
        prevState = currentState;
        this.state.setValue(newState);
        context.globalNotifyStateChanged(this, currentState);
    }

    /**
     * public API for service to report state. Allowed state are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's
     *                 actual state
     */
    public synchronized void reportState(State newState) {
        logger.atInfo().setEventType("service-report-state").addKeyValue("newState", newState).log();
        if (!ALLOWED_STATES_FOR_REPORTING.contains(newState)) {
            logger.atError().setEventType("service-invalid-state-error").addKeyValue("newState", newState)
                    .log("Invalid report state");
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
     */
    @SuppressWarnings({"checkstyle:emptycatchblock"})
    public static EvergreenService locate(Context context, String name) {
        return context.getv(EvergreenService.class, name).computeIfEmpty(v -> {
            Configuration configuration = context.get(Configuration.class);
            Topics topics = configuration.lookupTopics(Configuration.splitPath(name));
            assert (topics != null);
            if (topics.isEmpty()) {
                // No definition of this service was found in the config file.
                // weave config fragments in from elsewhere...
                Kernel kernel = context.get(Kernel.class);
                for (String serverUrl : kernel.getServiceServerURLList()) {
                    if (topics.isEmpty()) {
                        try {
                            // TODO: should probably think hard about what file extension to use
                            // TODO: allow the file to be a zip package?
                            URL configUrl = new URL(serverUrl + name + ".evg");
                            kernel.read(configUrl, false);
                            if (!topics.isEmpty()) {
                                staticLogger.atInfo().setEventType("service-config-found")
                                        .addKeyValue("configURL", configUrl).log("Found external service definition");
                            }
                        } catch (IOException ignored) {
                        }
                    } else {
                        break;
                    }
                }
                if (topics.isEmpty()) {
                    topics.createLeafChild("run").dflt("echo No definition found for " + name + ";exit -1");
                    staticLogger.atWarn().setEventType("service-config-not-found").addKeyValue("serviceName", name)
                            .log();
                }
            } else {
                staticLogger.atInfo().setEventType("service-config-found").addKeyValue("serviceName", name)
                        .log("Found service definition in configuration file");
            }
            EvergreenService ret;
            Class<?> clazz = null;
            Node n = topics.getChild("class");
            if (n != null) {
                String cn = Coerce.toString(n);
                try {
                    clazz = Class.forName(cn);
                } catch (Throwable ex) {
                    staticLogger.atError().setEventType("service-load-error").setCause(ex)
                            .addKeyValue("serviceName", name).log("Can't load service class");
                    return errNode(context, name, "Can't load service class from " + cn, ex);
                }
            }

            if (clazz == null) {
                Map<String, Class<?>> si = context.getIfExists(Map.class, "service-implementors");
                if (si != null) {
                    staticLogger.atDebug().addKeyValue("serviceName", name).log("Attempt to load service from plugins");
                    clazz = si.get(name);
                }
            }
            if (clazz != null) {
                try {
                    Constructor<?> ctor = clazz.getConstructor(Topics.class);
                    ret = (EvergreenService) ctor.newInstance(topics);
                    if (clazz.getAnnotation(Singleton.class) != null) {
                        context.put(ret.getClass(), v);
                    }
                    staticLogger.atInfo().setEventType("evergreen-service-loaded")
                            .addKeyValue("serviceName", ret.getName()).log();
                } catch (Throwable ex) {
                    staticLogger.atError().setCause(ex).setEventType("evergreen-service-load-error")
                            .addKeyValue("className", clazz.getName()).log("Can't create Evergreen Service instance");
                    ret = errNode(context, name, "Can't create code-backed service from " + clazz.getSimpleName(), ex);
                }
            } else if (topics.isEmpty()) {
                staticLogger.atError().setEventType("service-load-error").addKeyValue("serviceName", name)
                        .log("No matching definition in system model");
                ret = errNode(context, name, "No matching definition in system model", null);
            } else {
                try {
                    ret = new GenericExternalService(topics);
                    staticLogger.atInfo().setEventType("generic-service-loaded")
                            .addKeyValue("serviceName", ret.getName()).log();
                } catch (Throwable ex) {
                    staticLogger.atError().setCause(ex).setEventType("generic-service-load-error")
                            .addKeyValue("serviceName", Coerce.toString(topics)).log("Can't create generic instance");
                    ret = errNode(context, name, "Can't create generic service", ex);
                }
            }
            return ret;
        });
    }

    /**
     * Creates a GenericExternalService without a service definition. Creates it just to report an error.
     *
     * @param context static Context
     * @param name    name of the service which could not be constructed
     * @param message message for reason why there is an error
     * @param ex      exception which caused the error node to be used
     * @return Error service
     */
    public static EvergreenService errNode(Context context, String name, String message, Throwable ex) {
        try {
            return new GenericExternalService(Topics.errorNode(context, name,
                    "Error locating service " + name + ": " + message + (ex == null ? "" : "\n\t" + ex)));
        } catch (Throwable ex1) {
            staticLogger.atError().setCause(ex1).setEventType("service-error-report-error")
                    .addKeyValue("serviceName", name).addKeyValue("errorReport", message).log();
            return null;
        }
    }

    private Topic initStateTopic(final Topics topics) {
        Topic state = topics.createLeafChild(STATE_TOPIC_NAME);
        state.setParentNeedsToKnow(false);
        state.setValue(State.NEW);
        state.validate((newStateObj, oldStateObj) -> {
            State newState = Coerce.toEnum(State.class, newStateObj);
            return newState == null ? oldStateObj : newStateObj;
        });

        return state;
    }

    private synchronized void initRequiresTopic() {
        requiresTopic.subscribe((what, node) -> {
            if (!WhatHappened.changed.equals(what)) {
                return;
            }

            logger.atInfo().log("Setting up dependencies again", requiresTopic.getOnce());
            try {
                setupDependencies((String) requiresTopic.getOnce());
            } catch (Exception e) {
                logger.atError().log("Error while setting up dependencies from subscription", e);
            }
        });

        try {
            setupDependencies((String) requiresTopic.getOnce());
        } catch (Exception e) {
            serviceErrored(e);
        }
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
            logger.atInfo().setEventType("service-state-transition-start").addKeyValue("currentState", current).log();

            // if already in desired state, remove the head of desired state list.
            desiredState = peekOrRemoveFirstDesiredState(current);
            while (desiredState.isPresent() && desiredState.get().equals(current)) {
                desiredState = peekOrRemoveFirstDesiredState(current);
            }

            switch (current) {
                case BROKEN:
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
                            logger.atError().setEventType("service-install-error").setCause(t).log();
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
                                    logger.atInfo().setEventType("service-awaiting-start")
                                            .log("waiting for dependencies to start");
                                    waitForDependencyReady();
                                    logger.atInfo().setEventType("service-starting").log();
                                } catch (InterruptedException e) {
                                    logger.atError().setEventType("service-dependency-error").setCause(e)
                                            .log("Got interrupted while waiting for dependency ready");
                                    return;
                                }

                                try {
                                    // TODO: rename to  initiateStartup. Service need to report state to RUNNING.
                                    startup();
                                } catch (Throwable t) {
                                    reportState(State.ERRORED);
                                    logger.atError().setEventType("service-runtime-error").setCause(t).log();
                                }
                            }, "start");

                            break;
                        default:
                            // not allowed for NEW, STOPPING, ERRORED, BROKEN
                            logger.atError().setEventType("service-invalid-state-error")
                                    .addKeyValue("desiredState", desiredState).log("Unexpected desired state");
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
                    Thread stopThread = new Thread(() -> {
                        try {
                            shutdown();
                        } catch (Throwable t) {
                            logger.atError().setEventType("service-shutdown-error").setCause(t).log();
                            reportState(State.ERRORED);
                        } finally {
                            stopping.countDown();
                        }
                    });
                    stopThread.start();

                    boolean stopSucceed = stopping.await(15, TimeUnit.SECONDS);

                    stopBackingTask();
                    if (State.ERRORED.equals(getReportState().orElse(null)) || !stopSucceed) {
                        setState(State.ERRORED);
                        // If the thread is still running, then kill it
                        if (stopThread.isAlive()) {
                            stopThread.interrupt();
                        }
                        continue;
                    } else {
                        setState(State.FINISHED);
                        continue;
                    }

                case FINISHED:
                    if (!desiredState.isPresent()) {
                        break;
                    }

                    logger.atInfo().setEventType("service-state-transition").addKeyValue("currentState", getState())
                            .addKeyValue("desiredState", desiredState).log();
                    switch (desiredState.get()) {
                        case NEW:
                        case INSTALLED:
                            setState(State.NEW);
                            continue;
                        case RUNNING:
                            setState(State.INSTALLED);
                            continue;
                        default:
                            // not allowed to set desired state to STOPPING, ERRORED, BROKEN
                            logger.atError().setEventType("service-invalid-state-error")
                                    .addKeyValue("desiredState", desiredState).log("Unexpected desired state");
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
                            logger.atError().setEventType("service-invalid-state-error")
                                    .addKeyValue("previousState", prevState).log("Unexpected previous state");
                            setState(State.FINISHED);
                            continue;
                    }
                default:
                    logger.atError().setEventType("service-invalid-state-error").addKeyValue("currentState", getState())
                            .log("Unrecognized state");
                    break;
            }

            // blocking on event queue.
            // The state event can either be a report state transition event or a desired state updated event.
            // TODO: check if it's possible to move this blocking logic to the beginning of while loop.
            Object stateEvent = stateEventQueue.take();
            if (stateEvent instanceof State) {
                State toState = (State) stateEvent;
                logger.atInfo().setEventType("service-report-state").addKeyValue("state", toState).log();
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

    /**
     * Report that the service has hit an error.
     *
     * @param e Throwable issue that caused the error
     */
    public void serviceErrored(Throwable e) {
        e = getUltimateCause(e);
        error = e;
        serviceErrored();
    }

    public void serviceErrored() {
        reportState(State.ERRORED);
    }

    public boolean isErrored() {
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
        Periodicity t = periodicityInformation;
        if (t != null) {
            t.shutdown();
        }
        try {
            waitForDependersToExit();
        } catch (InterruptedException e) {
            logger.error("Interrupted waiting for dependers to exit");
        }
        requestStop();
    }

    /**
     * Shutdown the thread executing startStateTransition().
     */
    public void shutDownStateMachine() {
        closed.set(true);
    }

    public Context getContext() {
        return context;
    }

    /**
     * Add a dependency.
     *
     * @param dependentEvergreenService the service to add as a dependency.
     * @param when                      the state that the dependent service must be in before starting the current
     *                                  service.
     * @throws InputValidationException if the provided arguments are invalid.
     */
    public synchronized void addDependency(EvergreenService dependentEvergreenService, State when)
            throws InputValidationException {
        if (dependentEvergreenService == null || when == null) {
            throw new InputValidationException("One or more parameters was null");
        }

        if (dependencies.containsKey(dependentEvergreenService) && dependencies.get(dependentEvergreenService)
                .equals(when)) {
            return;
        }

        context.get(Kernel.class).clearODcache();
        dependencies.put(dependentEvergreenService, when);
        dependentEvergreenService.addDepender(this);
        String ser = serializeDependencyList(dependencies);
        requiresTopic.setValue(ser);

        dependentEvergreenService.getStateTopic().subscribe((WhatHappened what, Topic t) -> {
            if (this.getState() == State.INSTALLED || this.getState() == State.RUNNING) {
                if (!dependencyReady(dependentEvergreenService)) {
                    this.requestRestart();
                    logger.atInfo().setEventType("service-restart").log("Restart service because of dependencies");
                }
            }
            synchronized (dependencyReadyLock) {
                if (dependencyReady()) {
                    dependencyReadyLock.notifyAll();
                }
            }
        });
    }

    private void addDepender(EvergreenService dependerEvergreenService) {
        dependers.add(dependerEvergreenService);
        dependerEvergreenService.getStateTopic().subscribe((WhatHappened what, Topic t) -> {
            synchronized (dependersExitedLock) {
                if (dependersExited()) {
                    dependersExitedLock.notifyAll();
                }
            }
        });
    }

    private String serializeDependencyList(ConcurrentHashMap<EvergreenService, State> dependencies) {
        return dependencies.entrySet().stream().map((entry) -> entry.getKey().getName() + ":" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    private void waitForDependersToExit() throws InterruptedException {
        synchronized (dependersExitedLock) {
            while (!dependersExited()) {
                logger.atDebug().setEventType("service-waiting-for-depender-to-finish").log();
                dependersExitedLock.wait();
            }
        }
    }

    private boolean dependersExited() {
        Optional<EvergreenService> dependerService =
                dependers.stream().filter(d -> !dependerExited(d)).findAny();
        if (dependerService.isPresent()) {
            logger.atDebug().setEventType("continue-waiting-for-dependencies")
                    .addKeyValue("waitingFor", dependerService.get().getName()).log();
            return false;
        }
        return true;
    }

    private boolean dependerExited(EvergreenService dependerService) {
        return dependerService.getState().isTerminalState();
    }

    private boolean dependencyReady() {
        List<EvergreenService> ret =
                dependencies.keySet().stream().filter(d -> !dependencyReady(d)).collect(Collectors.toList());
        if (!ret.isEmpty()) {
            logger.atDebug().setEventType("continue-waiting-for-dependencies").addKeyValue("waitingFor", ret).log();
        }
        return ret.isEmpty();
    }

    private boolean dependencyReady(EvergreenService v) {
        State state = v.getState();
        State startWhenState = dependencies.get(v);
        return state.isHappy() && (startWhenState == null || startWhenState.preceedsOrEqual(state));
    }

    private void waitForDependencyReady() throws InterruptedException {
        synchronized (dependencyReadyLock) {
            while (!dependencyReady()) {
                logger.atDebug().setEventType("service-waiting-for-dependency").log();
                dependencyReadyLock.wait();
            }
        }
    }

    public void forAllDependencies(Consumer<? super EvergreenService> f) {
        dependencies.keySet().forEach(f);
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
        initRequiresTopic();
        //TODO: Use better threadPool mechanism
        new Thread(() -> {
            while (!closed.get()) {
                try {
                    startStateTransition();
                    return;
                } catch (Throwable e) {
                    logger.atError().setEventType("service-state-transition-error")
                            .addKeyValue("currentState", getState()).setCause(e).log();
                    logger.atInfo().setEventType("service-state-transition-retry")
                            .addKeyValue("currentState", getState()).log();
                }
            }
        }).start();
    }

    private Map<EvergreenService, State> parseDependencyList(String dependencyList) throws Exception {
        Matcher m = DEP_PARSE.matcher(dependencyList);
        HashMap<EvergreenService, State> ret = new HashMap<>();
        while (m.find()) {
            Pair<EvergreenService, State> dep = parseSingleDependency(m.group(1), m.group(3));
            ret.put(dep.getLeft(), dep.getRight());
        }
        if (!m.hitEnd()) {
            throw new Exception("Bad dependency syntax");
        }
        return ret;
    }

    private Pair<EvergreenService, State> parseSingleDependency(String name, String startWhen) throws Exception {
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
                throw new Exception(startWhen + " does not match any EvergreenService state name");
            }
        }

        EvergreenService d = locate(context, name);
        if (d != null) {
            return new Pair<>(d, x == null ? State.RUNNING : x);
        } else {
            throw new Exception("Could not locate service " + name);
        }
    }

    private synchronized void setupDependencies(String dependencyList) throws Exception {
        Map<EvergreenService, State> shouldHaveDependencies = parseDependencyList(dependencyList);
        shouldHaveDependencies.forEach((dependentEvergreenService, when) -> {
            try {
                addDependency(dependentEvergreenService, when);
            } catch (InputValidationException e) {
                logger.atWarn().setCause(e).setEventType("add-dependency")
                        .log("Unable to add dependency {}", dependentEvergreenService);
            }
        });

        Set<EvergreenService> removedDependencies =
                dependencies.keySet().stream().filter(d -> !shouldHaveDependencies.containsKey(d))
                        .collect(Collectors.toSet());
        if (!removedDependencies.isEmpty()) {
            logger.atInfo().setEventType("removing-unused-dependencies")
                    .addKeyValue("removedDependencies", removedDependencies);
            removedDependencies.forEach(dependencies::remove);
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
        dependencies.keySet().forEach(d -> {
            if (!deps.contains(d)) {
                d.addDependencies(deps);
            }
        });
    }

    public Map<EvergreenService, State> getDependencies() {
        return dependencies;
    }

    public boolean satisfiedBy(HashSet<EvergreenService> ready) {
        return ready.containsAll(dependencies.keySet());
    }

    public enum RunStatus {
        OK, NothingDone, Errored
    }

    public interface GlobalStateChangeListener {
        void globalServiceStateChanged(EvergreenService l, State was);
    }

}
