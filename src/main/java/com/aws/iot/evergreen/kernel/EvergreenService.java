/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Pair;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AllArgsConstructor;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.inject.Singleton;

import static com.aws.iot.evergreen.util.Utils.getUltimateCause;

public class EvergreenService implements InjectionActions {
    public static final String STATE_TOPIC_NAME = "_State";
    public static final String SERVICES_NAMESPACE_TOPIC = "services";
    public static final String SERVICE_LIFECYCLE_NAMESPACE_TOPIC = "lifecycle";
    public static final String SERVICE_NAME_KEY = "serviceName";

    public final Topics config;
    public Context context;

    private final Object dependencyReadyLock = new Object();
    private final Object dependersExitedLock = new Object();
    private final Topic state;
    private Throwable error;
    private Future backingTask;
    private Periodicity periodicityInformation;
    private State prevState = State.NEW;
    private Future<?> lifecycleFuture;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    // A state event can be a state transition event, or a desired state updated notification.
    // TODO: make class of StateEvent instead of generic object.
    private final BlockingQueue<Object> stateEventQueue = new ArrayBlockingQueue<>(1);
    private final Object stateEventLock = new Object();

    // DesiredStateList is used to set desired path of state transition.
    // Eg. Start a service will need DesiredStateList to be <RUNNING>
    // ReInstall a service will set DesiredStateList to <FINISHED->NEW->RUNNING>
    private final List<State> desiredStateList = new CopyOnWriteArrayList<>();

    private static final Set<State> ALLOWED_STATES_FOR_REPORTING =
            new HashSet<>(Arrays.asList(State.RUNNING, State.ERRORED, State.FINISHED));

    // dependencies that are explicilty declared by customer in config store.
    private final Topic externalDependenciesTopic;
    // Services that this service depends on.
    // Includes both explicit declared dependencies and implicit ones added through 'autoStart' and @Inject annotation.
    protected final ConcurrentHashMap<EvergreenService, DependencyInfo> dependencies = new ConcurrentHashMap<>();

    // Static logger instance for static methods
    private static final Logger staticLogger = LogManager.getLogger(EvergreenService.class);
    // Service logger instance
    protected final Logger logger;

    // Service lifecycle Topics
    protected final Topics lifecycle;

    /**
     * Constructor for EvergreenService.
     *
     * @param topics root Configuration topic for this service
     */
    public EvergreenService(Topics topics) {
        this.config = topics;
        this.context = topics.getContext();
        this.lifecycle = topics.findInteriorChild(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);

        // TODO: Validate syntax for lifecycle keywords and fail early
        // skipif will require validation for onpath/exists etc. keywords

        this.logger = LogManager.getLogger(getName());
        logger.addDefaultKeyValue(SERVICE_NAME_KEY, getName());
        this.state = initStateTopic(topics);

        this.externalDependenciesTopic = topics.createLeafChild("dependencies").dflt(new ArrayList<String>());
        this.externalDependenciesTopic.setParentNeedsToKnow(false);
    }

    public State getState() {
        return (State) state.getOnce();
    }

    private void updateStateAndBroadcast(State newState) {
        final State currentState = getState();

        if (newState.equals(currentState)) {
            return;
        }

        // TODO: Add validation
        logger.atInfo().setEventType("service-set-state").kv("currentState", currentState).kv("newState", newState)
                .log();

        // Sync on State.class to make sure the order of setValue and globalNotifyStateChanged are consistent
        // across different services.
        synchronized (State.class) {
            prevState = currentState;
            this.state.setValue(newState);
            context.globalNotifyStateChanged(this, prevState, newState);
        }
    }

    /**
     * public API for service to report state. Allowed state are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's
     *                 actual state
     */
    public synchronized void reportState(State newState) {
        logger.atInfo().setEventType("service-report-state").kv("newState", newState).log();
        if (!ALLOWED_STATES_FOR_REPORTING.contains(newState)) {
            logger.atError().setEventType("service-invalid-state-error").kv("newState", newState)
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
     * @throws ServiceLoadException if service cannot load
     */
    @SuppressWarnings({"checkstyle:emptycatchblock"})
    public static EvergreenService locate(Context context, String name) throws ServiceLoadException {
        return context.getv(EvergreenService.class, name).computeIfEmpty(v -> {
            Configuration configuration = context.get(Configuration.class);
            Topics serviceRootTopics = configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC, name);
            if (serviceRootTopics == null || serviceRootTopics.isEmpty()) {
                staticLogger.atWarn().setEventType("service-config-not-found").kv(SERVICE_NAME_KEY, name);
            } else {
                staticLogger.atInfo().setEventType("service-config-found").kv(SERVICE_NAME_KEY, name)
                        .log("Found service definition in configuration file");
            }
            EvergreenService ret;

            // try to find service implementation class from plugins.
            Class<?> clazz = null;
            Node n = null;

            if (serviceRootTopics != null) {
                n = serviceRootTopics.findLeafChild("class");
            }

            if (n != null) {
                String cn = Coerce.toString(n);
                try {
                    clazz = Class.forName(cn);
                } catch (Throwable ex) {
                    throw new ServiceLoadException("Can't load service class from " + cn, ex);
                }
            }

            if (clazz == null) {
                Map<String, Class<?>> si = context.getIfExists(Map.class, "service-implementors");
                if (si != null) {
                    staticLogger.atDebug().kv(SERVICE_NAME_KEY, name).log("Attempt to load service from plugins");
                    clazz = si.get(name);
                }
            }
            // If found class, try to load service class from plugins.
            if (clazz != null) {
                try {
                    Constructor<?> ctor = clazz.getConstructor(Topics.class);
                    ret = (EvergreenService) ctor.newInstance(serviceRootTopics);
                    if (clazz.getAnnotation(Singleton.class) != null) {
                        context.put(ret.getClass(), v);
                    }
                    staticLogger.atInfo().setEventType("evergreen-service-loaded").kv(SERVICE_NAME_KEY, ret.getName())
                            .log();
                } catch (Throwable ex) {
                    throw new ServiceLoadException("Can't create Evergreen Service instance " + clazz.getSimpleName(),
                            ex);
                }
            } else if (serviceRootTopics.isEmpty()) {
                throw new ServiceLoadException("No matching definition in system model");
            } else {
                // if not found, initialize GenericExternalService
                try {
                    ret = new GenericExternalService(serviceRootTopics);
                    staticLogger.atInfo().setEventType("generic-service-loaded").kv(SERVICE_NAME_KEY, ret.getName())
                            .log();
                } catch (Throwable ex) {
                    throw new ServiceLoadException("Can't create generic service instance " + name, ex);
                }
            }
            return ret;
        });
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

    private synchronized void initDependenciesTopic() {
        externalDependenciesTopic.subscribe((what, node) -> {
            if (!WhatHappened.changed.equals(what)) {
                return;
            }
            Iterable<String> depList = (Iterable<String>) node.getOnce();
            logger.atInfo().log("Setting up dependencies again", String.join(",", depList));
            try {
                setupDependencies(depList);
            } catch (Exception e) {
                logger.atError().log("Error while setting up dependencies from subscription", e);
            }
        });

        try {
            setupDependencies((Iterable<String>) externalDependenciesTopic.getOnce());
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

    /**
     * Returns true if the service has reached its desired state.
     * @return
     */
    public boolean reachedDesiredState() {
        synchronized (desiredStateList) {
            return desiredStateList.isEmpty()
                    // when reachedDesiredState() is called in global state listener,
                    // service lifecycle thread hasn't drained the desiredStateList yet.
                    // Therefore adding this check.
                    || desiredStateList.stream().allMatch(s -> s == getState());
        }
    }

    private Optional<State> peekOrRemoveFirstDesiredState(State activeState) {
        synchronized (desiredStateList) {
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
    }

    // Set desiredStateList and override existing desiredStateList.
    private void setDesiredState(State... state) {
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
    private void enqueueStateEvent(Object event) {
        synchronized (stateEventLock) {
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
    }

    /**
     * Start Service.
     */
    public final void requestStart() {
        synchronized (this.desiredStateList) {
            if (this.desiredStateList.isEmpty()) {
                this.setDesiredState(State.RUNNING);
                return;
            }
            State lastState = this.desiredStateList.get(this.desiredStateList.size() - 1);
            if (lastState == State.RUNNING) {
                return;
            } else if (lastState == State.FINISHED) {
                this.desiredStateList.set(this.desiredStateList.size() - 1, State.RUNNING);
            } else {
                this.desiredStateList.add(State.RUNNING);
            }
        }
    }

    /**
     * Stop Service.
     */
    public final void requestStop() {
        synchronized (this.desiredStateList) {
            // don't override in the case of re-install
            int index = this.desiredStateList.indexOf(State.NEW);
            if (index == -1) {
                setDesiredState(State.FINISHED);
                return;
            }
            this.desiredStateList.subList(index + 1, this.desiredStateList.size()).clear();
            this.desiredStateList.add(State.FINISHED);
        }
    }

    /**
     * Restart Service.
     */
    public final void requestRestart() {
        synchronized (this.desiredStateList) {
            // don't override in the case of re-install
            int index = this.desiredStateList.indexOf(State.NEW);
            if (index == -1) {
                setDesiredState(State.FINISHED, State.RUNNING);
                return;
            }
            this.desiredStateList.subList(index + 1, this.desiredStateList.size()).clear();
            this.desiredStateList.add(State.RUNNING);
        }
    }

    /**
     * ReInstall Service.
     */
    public final void requestReinstall() {
        synchronized (this.desiredStateList) {
            setDesiredState(State.FINISHED, State.NEW, State.RUNNING);
        }
    }

    private void startStateTransition() throws InterruptedException {
        periodicityInformation = Periodicity.of(this);
        while (!(isClosed.get() && getState().isClosable())) {
            Optional<State> desiredState;
            State current = getState();
            logger.atInfo().setEventType("service-state-transition-start").kv("currentState", current).log();

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
                        } catch (InterruptedException t) {
                            logger.atWarn("service-install-interrupted")
                                    .log("Service interrupted while running install");
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
                        updateStateAndBroadcast(State.ERRORED);
                    } else {
                        updateStateAndBroadcast(State.INSTALLED);
                    }
                    continue;
                case INSTALLED:
                    stopBackingTask();
                    if (!desiredState.isPresent()) {
                        break;
                    }

                    switch (desiredState.get()) {
                        case FINISHED:
                            updateStateAndBroadcast(State.FINISHED);
                            continue;
                        case RUNNING:
                            setBackingTask(() -> {
                                try {
                                    logger.atInfo().setEventType("service-awaiting-start")
                                            .log("waiting for dependencies to start");
                                    waitForDependencyReady();
                                    logger.atInfo().setEventType("service-starting").log();
                                } catch (InterruptedException e) {
                                    logger.atWarn().setEventType("service-dependency-error")
                                            .log("Got interrupted while waiting for dependency ready");
                                    return;
                                }

                                try {
                                    // TODO: rename to  initiateStartup. Service need to report state to RUNNING.
                                    startup();
                                } catch (InterruptedException i) {
                                    logger.atWarn("service-run-interrupted")
                                            .log("Service interrupted while running startup");
                                } catch (Throwable t) {
                                    reportState(State.ERRORED);
                                    logger.atError().setEventType("service-runtime-error").setCause(t).log();
                                }
                            }, "start");

                            break;
                        default:
                            // not allowed for NEW, STOPPING, ERRORED, BROKEN
                            logger.atError().setEventType("service-invalid-state-error")
                                    .kv("desiredState", desiredState).log("Unexpected desired state");
                            break;
                    }
                    break;
                case RUNNING:
                    if (!desiredState.isPresent()) {
                        break;
                    }

                    updateStateAndBroadcast(State.STOPPING);
                    continue;
                case STOPPING:
                    // doesn't handle desiredState in STOPPING.
                    // Not use setBackingTask because it will cancel the existing task.
                    CountDownLatch stopping = new CountDownLatch(1);
                    Future<?> shutdownFuture = context.get(ExecutorService.class).submit(() -> {
                        try {
                            shutdown();
                        } catch (InterruptedException i) {
                            logger.atWarn("service-shutdown-interrupted")
                                    .log("Service interrupted while running shutdown");
                        } catch (Throwable t) {
                            reportState(State.ERRORED);
                            logger.atError().setEventType("service-shutdown-error").setCause(t).log();
                        } finally {
                            stopping.countDown();
                        }
                    });

                    boolean stopSucceed = stopping.await(15, TimeUnit.SECONDS);

                    stopBackingTask();
                    if (State.ERRORED.equals(getReportState().orElse(null)) || !stopSucceed) {
                        updateStateAndBroadcast(State.ERRORED);
                        // If the thread is still running, then kill it
                        if (!shutdownFuture.isDone()) {
                            shutdownFuture.cancel(true);
                        }
                        continue;
                    } else {
                        updateStateAndBroadcast(State.FINISHED);
                        continue;
                    }

                case FINISHED:
                    if (!desiredState.isPresent()) {
                        break;
                    }

                    logger.atInfo().setEventType("service-state-transition").kv("currentState", getState())
                            .kv("desiredState", desiredState).log();
                    switch (desiredState.get()) {
                        case NEW:
                        case INSTALLED:
                            updateStateAndBroadcast(State.NEW);
                            continue;
                        case RUNNING:
                            updateStateAndBroadcast(State.INSTALLED);
                            continue;
                        default:
                            // not allowed to set desired state to STOPPING, ERRORED, BROKEN
                            logger.atError().setEventType("service-invalid-state-error")
                                    .kv("desiredState", desiredState).log("Unexpected desired state");
                    }
                    break;
                case ERRORED:
                    try {
                        handleError();
                    } catch (InterruptedException e) {
                        logger.atWarn("service-errorhandler-interrupted")
                                .log("Service interrupted while running error handler");
                        // Since we run the error handler in this thread, that means we should rethrow
                        // in order to shutdown this thread since we were requested to stop
                        throw e;
                    }
                    //TODO: Set service to broken state if error happens too often
                    if (!desiredState.isPresent()) {
                        requestStart();
                    }
                    switch (prevState) {
                        case RUNNING:
                            updateStateAndBroadcast(State.STOPPING);
                            continue;
                        case NEW: // error in installing.
                            updateStateAndBroadcast(State.NEW);
                            continue;
                        case INSTALLED: // error in starting
                            updateStateAndBroadcast(State.INSTALLED);
                            continue;
                        case STOPPING:
                            // not handled;
                            updateStateAndBroadcast(State.FINISHED);
                            continue;
                        default:
                            logger.atError().setEventType("service-invalid-state-error").kv("previousState", prevState)
                                    .log("Unexpected previous state");
                            updateStateAndBroadcast(State.FINISHED);
                            continue;
                    }
                default:
                    logger.atError().setEventType("service-invalid-state-error").kv("currentState", getState())
                            .log("Unrecognized state");
                    break;
            }

            // blocking on event queue.
            // The state event can either be a report state transition event or a desired state updated event.
            // TODO: check if it's possible to move this blocking logic to the beginning of while loop.
            Object stateEvent = stateEventQueue.take();
            if (stateEvent instanceof State) {
                State toState = (State) stateEvent;
                logger.atInfo().setEventType("service-report-state").kv("state", toState).log();
                updateStateAndBroadcast(toState);
            }
        }
    }

    /**
     * Custom handler to handle error.
     *
     * @throws InterruptedException if the thread is interrupted while handling the error
     */
    public void handleError() throws InterruptedException {
    }

    private synchronized void setBackingTask(Runnable r, String db) {
        Future bt = backingTask;
        if (bt != null) {
            backingTask = null;
            if (!bt.isDone()) {
                logger.info("Stopping backingTask {}", bt);
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
     *
     * @throws InterruptedException if the install task was interrupted while running
     */
    protected void install() throws InterruptedException {
    }

    /**
     * Called when all dependencies are RUNNING. If there are no dependencies,
     * it is called right after postInject.  The service doesn't transition to RUNNING
     * until *after* this state is complete.
     *
     * @throws InterruptedException if the startup task was interrupted while running
     */
    protected void startup() throws InterruptedException {
        reportState(State.RUNNING);
    }

    /**
     * Called when the object's state leaves RUNNING.
     *
     * @throws InterruptedException if the shutdown task was interrupted while running
     */
    protected void shutdown() throws InterruptedException {
        Periodicity t = periodicityInformation;
        if (t != null) {
            t.shutdown();
        }
    }

    /**
     * Moves the service to finished state and shuts down lifecycle thread.
     *
     * @return future completes when the lifecycle thread shuts down.
     */
    public Future<Void> close() {
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        context.get(Executor.class).execute(() -> {
            try {
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
                isClosed.set(true);
                lifecycleFuture.get();
                closeFuture.complete(null);
            } catch (Exception e) {
                closeFuture.completeExceptionally(e);
            }
        });
        return closeFuture;
    }

    public Context getContext() {
        return context;
    }

    /**
     * Add a dependency.
     *
     * @param dependentEvergreenService the service to add as a dependency.
     * @param startWhen                      the state that the dependent service must be in before starting the current
     *                                  service.
     * @param isDefault                 True if the dependency is added without explicit declaration
     *                                  in 'dependencies' Topic.
     * @throws InputValidationException if the provided arguments are invalid.
     */
    public synchronized void addOrUpdateDependency(
            EvergreenService dependentEvergreenService, State startWhen, boolean isDefault)
            throws InputValidationException {
        if (dependentEvergreenService == null || startWhen == null) {
            throw new InputValidationException("One or more parameters was null");
        }

        dependencies.compute(dependentEvergreenService, (dependentService, dependencyInfo) -> {
            if (dependencyInfo == null) {
                Subscriber subscriber = createDependencySubscriber(dependentEvergreenService);
                dependentEvergreenService.getStateTopic().subscribe(subscriber);
                context.get(Kernel.class).clearODcache();
                return new DependencyInfo(startWhen, isDefault, subscriber);
            } else {
                dependencyInfo.startWhen = startWhen;
                // if a dependency is added as both a default and a non-default, treat it as default dependency
                if (!dependencyInfo.isDefaultDependency) {
                    dependencyInfo.isDefaultDependency = isDefault;
                }
                return dependencyInfo;
            }
        });
    }

    private Subscriber createDependencySubscriber(EvergreenService dependentEvergreenService) {
        return (WhatHappened what, Topic t) -> {
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
        };
    }

    private List<EvergreenService> getDependers() {
        List<EvergreenService> dependers = new ArrayList<>();
        Kernel kernel = context.get(Kernel.class);
        for (EvergreenService evergreenService : kernel.orderedDependencies()) {
            boolean isDepender = evergreenService.dependencies.keySet().stream().anyMatch(d -> d.equals(this));
            if (isDepender) {
                dependers.add(evergreenService);
            }
        }
        return dependers;
    }

    private void waitForDependersToExit() throws InterruptedException {

        List<EvergreenService> dependers = getDependers();
        Subscriber dependerExitWatcher = (WhatHappened what, Topic t) -> {
            synchronized (dependersExitedLock) {
                if (dependersExited(dependers)) {
                    dependersExitedLock.notifyAll();
                }
            }
        };
        // subscribing to depender state changes
        dependers.forEach(
                dependerEvergreenService -> dependerEvergreenService.getStateTopic().subscribe(dependerExitWatcher));

        synchronized (dependersExitedLock) {
            while (!dependersExited(dependers)) {
                logger.atDebug().setEventType("service-waiting-for-depender-to-finish").log();
                dependersExitedLock.wait();
            }
        }
        // removing state change watchers
        dependers.forEach(
                dependerEvergreenService -> dependerEvergreenService.getStateTopic().remove(dependerExitWatcher));
    }

    private boolean dependersExited(List<EvergreenService> dependers) {
        Optional<EvergreenService> dependerService =
                dependers.stream().filter(d -> !d.getState().isClosable()).findAny();
        if (dependerService.isPresent()) {
            logger.atDebug().setEventType("continue-waiting-for-dependencies")
                    .kv("waitingFor", dependerService.get().getName()).log();
            return false;
        }
        return true;
    }

    private boolean dependencyReady() {
        List<EvergreenService> ret =
                dependencies.keySet().stream().filter(d -> !dependencyReady(d)).collect(Collectors.toList());
        if (!ret.isEmpty()) {
            logger.atDebug().setEventType("continue-waiting-for-dependencies").kv("waitingFor", ret).log();
        }
        return ret.isEmpty();
    }

    private boolean dependencyReady(EvergreenService v) {
        State state = v.getState();
        State startWhenState = dependencies.get(v).startWhen;
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

    public String getName() {
        return config == null ? getClass().getSimpleName() : config.getName();
    }

    @Override
    public void postInject() {
        initDependenciesTopic();
        lifecycleFuture = context.get(ExecutorService.class).submit(() -> {
            while (!isClosed.get()) {
                try {
                    startStateTransition();
                    return;
                } catch (InterruptedException i) {
                    logger.atWarn().setEventType("service-state-transition-interrupted")
                            .log("Service lifecycle thread interrupted. Thread will exit now");
                    return;
                } catch (Throwable e) {
                    logger.atError().setEventType("service-state-transition-error").kv("currentState", getState())
                            .setCause(e).log();
                    logger.atInfo().setEventType("service-state-transition-retry").kv("currentState", getState()).log();
                }
            }
        });
    }

    private Map<EvergreenService, State> getDependencyStateMap(Iterable<String> dependencyList)
            throws InputValidationException, ServiceLoadException {
        HashMap<EvergreenService, State> ret = new HashMap<>();
        for (String dependency : dependencyList) {
            String[] dependencyInfo = dependency.split(":");
            if (dependencyInfo.length == 0 || dependencyInfo.length > 2) {
                throw new InputValidationException("Bad dependency syntax");
            }
            Pair<EvergreenService, State> dep =
                    parseSingleDependency(dependencyInfo[0], dependencyInfo.length > 1 ? dependencyInfo[1] : null);
            ret.put(dep.getLeft(), dep.getRight());
        }
        return ret;
    }

    private Pair<EvergreenService, State> parseSingleDependency(String name, String startWhen)
            throws InputValidationException, ServiceLoadException {
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
                throw new InputValidationException(startWhen + " does not match any EvergreenService state name");
            }
        }

        EvergreenService d = locate(context, name);
        return new Pair<>(d, x == null ? State.RUNNING : x);
    }

    private synchronized void setupDependencies(Iterable<String> dependencyList) throws Exception {
        Map<EvergreenService, State> oldDependencies = new HashMap<>(getDependencies());
        Map<EvergreenService, State> keptDependencies = getDependencyStateMap(dependencyList);

        Set<EvergreenService> removedDependencies = dependencies.entrySet().stream()
                .filter(e -> !keptDependencies.containsKey(e.getKey()) && !e.getValue().isDefaultDependency)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (!removedDependencies.isEmpty()) {
            logger.atInfo().setEventType("removing-unused-dependencies")
                    .addKeyValue("removedDependencies", removedDependencies);

            removedDependencies.forEach(dependency -> {
                DependencyInfo dependencyInfo = dependencies.remove(dependency);
                dependency.getStateTopic().remove(dependencyInfo.stateTopicSubscriber);
            });
            context.get(Kernel.class).clearODcache();
        }

        AtomicBoolean hasNewService = new AtomicBoolean(false);
        keptDependencies.forEach((dependentEvergreenService, startWhen) -> {
            try {
                if (!oldDependencies.containsKey(dependentEvergreenService)) {
                    hasNewService.set(true);
                }
                addOrUpdateDependency(dependentEvergreenService, startWhen, false);
            } catch (InputValidationException e) {
                logger.atWarn().setCause(e).setEventType("add-dependency")
                        .log("Unable to add dependency {}", dependentEvergreenService);
            }
        });

        if (hasNewService.get()) {
            requestRestart();
        } else if (!dependencyReady() && !getState().equals(State.FINISHED)) {
            // if dependency 'startWhen' changed, restart this service.
            requestRestart();
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

    protected void addDependencies(Set<EvergreenService> deps) {
        deps.add(this);
        dependencies.keySet().forEach(d -> {
            if (!deps.contains(d)) {
                d.addDependencies(deps);
            }
        });
    }

    //TODO: return the entire dependency info
    public Map<EvergreenService, State> getDependencies() {
        return dependencies.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().startWhen));
    }

    public boolean satisfiedBy(Set<EvergreenService> ready) {
        return ready.containsAll(dependencies.keySet());
    }

    public enum RunStatus {
        OK, NothingDone, Errored
    }

    public interface GlobalStateChangeListener {
        void globalServiceStateChanged(EvergreenService l, State oldState, State newState);
    }

    /**
     * is state machine shutting down.
     *
     * @return true is state machine is shutting down.
     */
    public boolean isClosed() {
        return isClosed.get();
    }

    @AllArgsConstructor
    protected static class DependencyInfo {
        // starting at which state when the dependency is considered Ready. Default to be RUNNING.
        State startWhen;
        // true if the dependency isn't explicitly declared in config
        boolean isDefaultDependency;
        Subscriber stateTopicSubscriber;
    }
}
