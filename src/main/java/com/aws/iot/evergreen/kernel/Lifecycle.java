/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.util.Coerce;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

@SuppressFBWarnings(value = "JLM_JSR166_UTILCONCURRENT_MONITORENTER",
        justification = "We're synchronizing on the desired state list which is fine")
public class Lifecycle {
    public static final String LIFECYCLE_INSTALL_NAMESPACE_TOPIC = "install";
    public static final String LIFECYCLE_STARTUP_NAMESPACE_TOPIC = "startup";
    public static final String LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC = "shutdown";
    public static final String TIMEOUT_NAMESPACE_TOPIC = "timeout";
    public static final String ERROR_RESET_TIME_TOPIC = "errorResetTime";

    public static final String STATE_TOPIC_NAME = "_State";
    private static final String NEW_STATE_METRIC_NAME = "newState";

    private static final Integer DEFAULT_INSTALL_STAGE_TIMEOUT_IN_SEC = 120;
    private static final Integer DEFAULT_STARTUP_STAGE_TIMEOUT_IN_SEC = 120;
    private static final Integer DEFAULT_SHUTDOWN_STAGE_TIMEOUT_IN_SEC = 15;
    private static final String INVALID_STATE_ERROR_EVENT = "service-invalid-state-error";
    // The maximum number of ERRORED before transitioning the service state to BROKEN.
    private static final int MAXIMUM_CONTINUAL_ERROR = 3;
    private static final long DEFAULT_ERROR_RESET_TIME_IN_SEC = Duration.ofHours(1).getSeconds();

    /*
     * State generation is a value representing how many times the service has been in the NEW/STARTING state.
     * It is to used determine if an action should be taken when that action would be run asynchronously.
     * It is not sufficient to check if the state is what you want it to be, because the service may have
     * restarted again by the time you are performing this check. Therefore, the generation is used to know
     * that the service is still in the same state as you want it to be.
     *
     * For example, if we want to move the service to errored if installed takes too long, then we setup a callback
     * to move it to errored if the state is installed. But this won't necessarily be correct because the service
     * could have restarted in the mean time, so this old callback should not move it to errored since it's view
     * of the world is outdated. If the callback checks both the state and the generation then it is assured to
     * properly move the service into errored only when the callback's view of the world is correct.
     */
    @Getter(AccessLevel.PACKAGE)
    private final AtomicLong stateGeneration = new AtomicLong();
    private final EvergreenService evergreenService;


    // lastReportedState stores the last reported state (not necessarily processed)
    private final AtomicReference<State> lastReportedState = new AtomicReference<>();
    private final Topic stateTopic;
    private final Logger logger;
    private final AtomicReference<Future> backingTask = new AtomicReference<>(CompletableFuture.completedFuture(null));
    private String backingTaskName;

    private Future<?> lifecycleThread;
    // A state event can be a reported state event, or a desired state updated notification.
    // TODO: make class of StateEvent instead of generic object.
    private final BlockingQueue<Object> stateEventQueue = new LinkedBlockingQueue<>();
    // DesiredStateList is used to set desired path of state transition.
    // Eg. Start a service will need DesiredStateList to be <RUNNING>
    // ReInstall a service will set DesiredStateList to <FINISHED->NEW->RUNNING>
    private final List<State> desiredStateList = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    private static final Map<State, Collection<State>> ALLOWED_STATE_TRANSITION_FOR_REPORTING = new HashMap<>();
    // The number of continual occurrences from a state to ERRORED.
    // This is not thread safe and should only be used inside reportState().
    private final Map<State, List<Long>> stateToErroredCount = new HashMap<>();
    // We only need to track the ERROR from these states because
    // they impact whether the service can function as expected.
    private static final Set<State> STATES_TO_ERRORED =
            new HashSet<>(Arrays.asList(State.NEW, State.STARTING, State.RUNNING));

    static {
        ALLOWED_STATE_TRANSITION_FOR_REPORTING.put(State.NEW, Arrays.asList(State.ERRORED));
        ALLOWED_STATE_TRANSITION_FOR_REPORTING
                .put(State.STARTING, new HashSet<>(Arrays.asList(State.RUNNING, State.ERRORED, State.FINISHED)));
        ALLOWED_STATE_TRANSITION_FOR_REPORTING
                .put(State.RUNNING, new HashSet<>(Arrays.asList(State.ERRORED, State.FINISHED)));
        ALLOWED_STATE_TRANSITION_FOR_REPORTING
                .put(State.STOPPING, new HashSet<>(Arrays.asList(State.ERRORED, State.FINISHED)));
    }

    /**
     * Constructor for lifecycle.
     *
     * @param evergreenService service that this is the lifecycle for
     * @param logger           service's logger
     */
    public Lifecycle(EvergreenService evergreenService, Logger logger) {
        this.evergreenService = evergreenService;
        this.stateTopic = initStateTopic(evergreenService.getConfig());
        this.logger = logger;
    }

    synchronized void reportState(State newState) {
        State lastState = lastReportedState.get();
        if (lastState == null) {
            lastState = getState();
        }

        Collection<State> allowedStatesForReporting = ALLOWED_STATE_TRANSITION_FOR_REPORTING.get(lastState);
        if (allowedStatesForReporting == null || !allowedStatesForReporting.contains(newState)) {
            logger.atWarn(INVALID_STATE_ERROR_EVENT).kv(NEW_STATE_METRIC_NAME, newState).log("Invalid reported state");
            return;
        }

        internalReportState(newState);
    }

    /**
     * public API for service to report state. Allowed state are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's
     *                 actual state
     */
    private synchronized void internalReportState(State newState) {
        logger.atInfo("service-report-state").kv(NEW_STATE_METRIC_NAME, newState).log();
        lastReportedState.set(newState);

        if (getState().equals(State.STARTING) && newState.equals(State.FINISHED)) {
            // if a service doesn't have any run logic, request stop on service to clean up DesiredStateList
            requestStop();
        }

        State currentState = getState();

        if (State.ERRORED.equals(newState) && STATES_TO_ERRORED.contains(currentState)) {
            // If the reported state is ERRORED, we'll increase the ERROR counter for the current state.
            stateToErroredCount.compute(currentState, (k, v) -> {
                if (v == null) {
                    v = new ArrayList<>();
                }

                final long now = evergreenService.getContext().get(Clock.class).millis();
                if (v.size() > 0 && now - v.get(v.size() - 1) >= getErrorResetTime() * 1000L) {
                    v.clear();
                }

                v.add(now);
                return v;
            });
        } else {
            // If the reported state is a non-ERRORED state, we would like to reset the ERROR counter for the current
            // state. This is to avoid putting the service to BROKEN state because of transient issues.
            stateToErroredCount.put(currentState, null);
        }
        if (stateToErroredCount.get(currentState) != null
                && stateToErroredCount.get(currentState).size() >= MAXIMUM_CONTINUAL_ERROR) {
            enqueueStateEvent(State.BROKEN);
        } else {
            enqueueStateEvent(newState);
        }
    }

    /**
     * Returns true if either the current or the very last reported state (if any)
     * is equal to the provided state.
     *
     * @param state state to check against
     */
    public boolean currentOrReportedStateIs(State state) {
        if (state.equals(getState())) {
            return true;
        }
        return state.equals(lastReportedState.get());
    }

    protected State getState() {
        return (State) stateTopic.getOnce();
    }

    protected Topic getStateTopic()  {
        return stateTopic;
    }

    private Topic initStateTopic(final Topics topics) {
        Topic state = topics.createLeafChild(STATE_TOPIC_NAME);
        state.withParentNeedsToKnow(false);
        state.withValue(State.NEW);
        state.addValidator((newStateObj, oldStateObj) -> {
            State newState = Coerce.toEnum(State.class, newStateObj);
            return newState == null ? oldStateObj : newStateObj;
        });

        return state;
    }

    /**
     * Returns true if the service has reached its desired state.
     *
     * @return
     */
    protected boolean reachedDesiredState() {
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
            if (first.equals(activeState)) {
                desiredStateList.remove(first);
                // ignore remove() return value as it's possible that desiredStateList update
            }
            return Optional.ofNullable(first);
        }
    }

    private void setDesiredState(State... state) {
        // Set desiredStateList and override existing desiredStateList.
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

    private void enqueueStateEvent(Object event) {
        if (!stateEventQueue.offer(event)) {
            logger.error("couldn't put the new event to stateEventQueue");
        }
    }

    private void startStateTransition() throws InterruptedException {
        AtomicReference<Predicate<Object>> asyncFinishAction = new AtomicReference<>((stateEvent) -> true);
        State prevState = getState();
        while (!(isClosed.get() && getState().isClosable())) {
            Optional<State> desiredState;
            State current = getState();
            logger.atDebug("service-state-transition-start").log();

            // if already in desired state, remove the head of desired state list.
            desiredState = peekOrRemoveFirstDesiredState(current);
            while (desiredState.isPresent() && desiredState.get().equals(current)) {
                desiredState = peekOrRemoveFirstDesiredState(current);
            }

            switch (current) {
                case BROKEN:
                    handleCurrentStateBroken(desiredState);
                    break;
                case NEW:
                    handleCurrentStateNew(desiredState);
                    break;
                case INSTALLED:
                    handleCurrentStateInstalledAsync(desiredState, asyncFinishAction);
                    break;
                case STARTING:
                    handleCurrentStateStartingAsync(desiredState, asyncFinishAction);
                    break;
                case RUNNING:
                    handleCurrentStateRunning(desiredState);
                    break;
                case STOPPING:
                    handleCurrentStateStopping();
                    break;
                case FINISHED:
                    handleCurrentStateFinished(desiredState);
                    break;
                case ERRORED:
                    handleCurrentStateErrored(desiredState, prevState);
                    break;
                default:
                    logger.atError(INVALID_STATE_ERROR_EVENT).log("Unrecognized current state");
                    break;
            }

            boolean canFinish = false;
            while (!canFinish) {
                // A state event can either be a report state transition event or a desired state updated event.
                Object stateEvent = stateEventQueue.poll();

                // If there are accumulated "DesiredStateUpdated" in the queue,
                // drain them until a "State" event is encountered.
                while (!(stateEvent instanceof State) && !stateEventQueue.isEmpty()) {
                    stateEvent = stateEventQueue.poll();
                }

                // if there are no events in the queue, block until one is available.
                if (stateEvent == null) {
                    stateEvent = stateEventQueue.take();
                }

                if (stateEvent instanceof State) {
                    State newState = (State) stateEvent;
                    if (newState == current) {
                        continue;
                    }

                    canFinish = true;
                    setState(current, newState);
                    prevState = current;
                }
                if (asyncFinishAction.get().test(stateEvent)) {
                    canFinish = true;
                }
            }
            asyncFinishAction.set((stateEvent) -> true);
        }
    }

    /**
     * !!WARNING!!
     * This method is package-private for unit testing purposes, but it must NEVER be called
     * from anything but the lifecycle thread in this class.
     *
     * @param current current state to transition out of
     * @param newState new state to transition into
     */
    void setState(State current, State newState) {
        logger.atInfo("service-set-state").kv(NEW_STATE_METRIC_NAME, newState).log();
        // Sync on State.class to make sure the order of setValue and globalNotifyStateChanged
        // are consistent across different services.
        synchronized (State.class) {
            stateTopic.withValue(newState);
            evergreenService.getContext().globalNotifyStateChanged(evergreenService, current, newState);
        }
    }

    private void handleCurrentStateBroken(Optional<State> desiredState) {
        if (!desiredState.isPresent()) {
            return;
        }
        // Having State.NEW as the desired state indicates the service is requested to reinstall, so here
        // we'll transition out of BROKEN state to give it a new chance.
        if (State.NEW.equals(desiredState.get())) {
            internalReportState(State.NEW);
        } else {
            logger.atError("service-broken").log("service is broken. Deployment is needed");
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private void handleCurrentStateNew(Optional<State> desiredState) throws InterruptedException {
        // if no desired state is set, don't do anything.
        if (!desiredState.isPresent()) {
            return;
        }

        long currentStateGeneration = stateGeneration.incrementAndGet();
        replaceBackingTask(() -> {
            if (!State.NEW.equals(getState()) || getStateGeneration().get() != currentStateGeneration) {
                // Bail out if we're not in the expected state
                return;
            }
            try {
                evergreenService.install();
            } catch (InterruptedException t) {
                logger.atWarn("service-install-interrupted").log("Service interrupted while running install");
            } catch (Throwable t) {
                evergreenService.serviceErrored(t);
            }
        }, "install");

        Integer installTimeOut = getTimeoutConfigValue(
                LIFECYCLE_INSTALL_NAMESPACE_TOPIC, DEFAULT_INSTALL_STAGE_TIMEOUT_IN_SEC);

        try {
            backingTask.get().get(installTimeOut, TimeUnit.SECONDS);
            if (!State.ERRORED.equals(lastReportedState.get())) {
                internalReportState(State.INSTALLED);
            }
        } catch (ExecutionException ee) {
            evergreenService.serviceErrored(ee);
        } catch (TimeoutException te) {
            evergreenService.serviceErrored("Timeout in install");
        } finally {
            stopBackingTask();
        }
    }


    private void handleCurrentStateInstalledAsync(Optional<State> desiredState,
                                                  AtomicReference<Predicate<Object>> asyncFinishAction) {
        if (!desiredState.isPresent()) {
            return;
        }

        if (!desiredState.get().equals(State.RUNNING)) {
            serviceTerminatedMoveToDesiredState(desiredState.get());
            return;
        }

        replaceBackingTask(() -> {
            try {
                logger.atInfo("service-awaiting-start").log("waiting for dependencies to start");
                evergreenService.waitForDependencyReady();
                logger.atInfo("service-starting").log();
                internalReportState(State.STARTING);
            } catch (InterruptedException e) {
                logger.atWarn("service-dependency-error").log("Got interrupted while waiting for dependency ready");
            }
        }, "waiting for dependency ready");

        asyncFinishAction.set((stateEvent) -> {
            stopBackingTask();
            return true;
        });
    }

    private void handleCurrentStateStartingAsync(Optional<State> desiredState,
                                                  AtomicReference<Predicate<Object>> asyncFinishAction) {
        if (!desiredState.isPresent()) {
            return;
        }

        if (desiredState.get().equals(State.RUNNING)) {
            // if there is already a startup() task running, do nothing.
            Future<?> currentTask = backingTask.get();
            if (currentTask != null && !currentTask.isDone()) {
                return;
            }
            handleStateTransitionStartingToRunningAsync(asyncFinishAction);
        } else {
            internalReportState(State.STOPPING);
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.AvoidGettingFutureWithoutTimeout"})
    private void handleStateTransitionStartingToRunningAsync(AtomicReference<Predicate<Object>> asyncFinishAction) {
        stateGeneration.incrementAndGet();
        Integer timeout = getTimeoutConfigValue(
                LIFECYCLE_STARTUP_NAMESPACE_TOPIC, DEFAULT_STARTUP_STAGE_TIMEOUT_IN_SEC);
        Future<?> schedule =
            evergreenService.getContext().get(ScheduledExecutorService.class).schedule(() -> {
                evergreenService.serviceErrored("startup timeout");
            }, timeout, TimeUnit.SECONDS);

        replaceBackingTask(() -> {
            try {
                if (!evergreenService.dependencyReady()) {
                    internalReportState(State.INSTALLED);
                    return;
                }
                evergreenService.startup();
            } catch (InterruptedException i) {
                logger.atWarn("service-run-interrupted").log("Service interrupted while running startup");
            } catch (Throwable t) {
                evergreenService.serviceErrored(t);
            }
        }, "start");

        asyncFinishAction.set((Object stateEvent) -> {
            // if a state is reported
            if (stateEvent instanceof State) {
                schedule.cancel(true);
                return true;
            }

            // else if desiredState is updated
            Optional<State> nextDesiredState = peekOrRemoveFirstDesiredState(State.STARTING);
            // Don't finish the state handling if the new desiredState is still RUNNING
            if (nextDesiredState.isPresent() && nextDesiredState.get().equals(State.RUNNING)) {
                return false;
            }

            schedule.cancel(true);
            return true;
        });
    }


    private void handleCurrentStateRunning(Optional<State> desiredState) {
        if (!desiredState.isPresent()) {
            return;
        }
        // desired state is different, let's transition to stopping state first.
        internalReportState(State.STOPPING);
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private void handleCurrentStateStopping() throws InterruptedException {
        // does not handle desiredState in STOPPING because we must stop first.
        // does not use setBackingTask because it will cancel the existing task.
        Future<?> shutdownFuture = evergreenService.getContext().get(ExecutorService.class).submit(() -> {
            try {
                evergreenService.shutdown();
            } catch (InterruptedException i) {
                logger.atWarn("service-shutdown-interrupted").log("Service interrupted while running shutdown");
            } catch (Throwable t) {
                evergreenService.serviceErrored(t);
            }
        });

        try {
            Integer timeout = getTimeoutConfigValue(
                        LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, DEFAULT_SHUTDOWN_STAGE_TIMEOUT_IN_SEC);
            shutdownFuture.get(timeout, TimeUnit.SECONDS);
            if (!State.ERRORED.equals(lastReportedState.get())) {
                Optional<State> desiredState = peekOrRemoveFirstDesiredState(State.FINISHED);
                serviceTerminatedMoveToDesiredState(desiredState.orElse(State.FINISHED));
            }
        } catch (ExecutionException ee) {
            evergreenService.serviceErrored(ee);
        } catch (TimeoutException te) {
            shutdownFuture.cancel(true);
            evergreenService.serviceErrored("Timeout in shutdown");
        } finally {
            stopBackingTask();
        }
    }

    private void handleCurrentStateFinished(Optional<State> desiredState) {
        if (!desiredState.isPresent()) {
            return;
        }

        serviceTerminatedMoveToDesiredState(desiredState.get());
    }

    private void handleCurrentStateErrored(Optional<State> desiredState, State prevState) throws InterruptedException {
        try {
            evergreenService.handleError();
        } catch (InterruptedException e) {
            logger.atWarn("service-errorhandler-interrupted").log("Service interrupted while running error handler");
            // Since we run the error handler in this thread, that means we should rethrow
            // in order to shutdown this thread since we were requested to stop
            throw e;
        }

        if (!desiredState.isPresent()) {
            // Reset the desired state to RUNNING to retry the ERROR.
            requestStart();
        }

        switch (prevState) {
            // For both starting and running, make sure we stop first before retrying
            case STARTING:
            case RUNNING:
                internalReportState(State.STOPPING);
                break;
            case NEW: // error in installing.
                internalReportState(State.NEW);
                break;
            case STOPPING:
                // not handled;
                desiredState = peekOrRemoveFirstDesiredState(State.FINISHED);
                serviceTerminatedMoveToDesiredState(desiredState.orElse(State.FINISHED));
                break;
            default:
                logger.atError(INVALID_STATE_ERROR_EVENT).kv("previousState", prevState)
                        .log("Unexpected previous state");
                internalReportState(State.FINISHED);
                break;
        }
    }

    /**
     * Given the service is terminated, move to desired state.
     * Only use in service lifecycle thread.
     *
     * @param desiredState the desiredState to go, not null
     */
    @SuppressWarnings("PMD.MissingBreakInSwitch")
    private void serviceTerminatedMoveToDesiredState(@Nonnull State desiredState) {
        if (isClosed.get()) {
            internalReportState(State.FINISHED);
            return;
        }
        switch (desiredState) {
            case NEW:
                internalReportState(State.NEW);
                break;
            case INSTALLED:
            case RUNNING:
                internalReportState(State.INSTALLED);
                break;
            case FINISHED:
                internalReportState(State.FINISHED);
                break;
            default:
                // not allowed to set desired state to STOPPING, ERRORED, BROKEN
                logger.atError(INVALID_STATE_ERROR_EVENT).kv("desiredState", desiredState)
                        .log("Unexpected desired state");
        }
    }

    @SuppressWarnings("PMD.AvoidGettingFutureWithoutTimeout")
    private synchronized Future<?> replaceBackingTask(Runnable r, String action) {
        Future<?> bt = backingTask.get();
        String btName = backingTaskName;

        if (bt != null && !bt.isDone()) {
            backingTask.set(CompletableFuture.completedFuture(null));
            logger.info("Stopping backingTask {}", btName);
            bt.cancel(true);
        }

        if (r != null) {
            backingTaskName = action;
            logger.debug("Scheduling backingTask {}", backingTaskName);
            backingTask.set(evergreenService.getContext().get(ExecutorService.class).submit(r));
        }
        return bt;
    }

    private Future<?> stopBackingTask() {
        return replaceBackingTask(null, null);
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    synchronized void initLifecycleThread() {
        if (lifecycleThread != null) {
            return;
        }
        lifecycleThread = evergreenService.getContext().get(ExecutorService.class).submit(() -> {
            while (!isClosed.get()) {
                try {
                    Thread.currentThread().setName(evergreenService.getName() + "-lifecycle");
                    startStateTransition();
                    return;
                } catch (RejectedExecutionException e) {
                    logger.atWarn("service-state-transition-error", e)
                            .log("Service lifecycle thread had RejectedExecutionException."
                                    + "Since no more tasks can be run, thread will exit now");
                    return;
                } catch (InterruptedException i) {
                    logger.atWarn("service-state-transition-interrupted")
                            .log("Service lifecycle thread interrupted. Thread will exit now");
                    return;
                } catch (Throwable e) {
                    logger.atError("service-state-transition-error").setCause(e).log();
                    logger.atInfo("service-state-transition-retry").log();
                }
            }
        });
    }

    public synchronized Future<?> getLifecycleThread() {
        return lifecycleThread;
    }

    void setClosed(boolean b) {
        isClosed.set(b);
    }

    /**
     * Start Service.
     */
    final void requestStart() {
        // Ignore start requests if the service is closed
        if (isClosed.get()) {
            return;
        }
        synchronized (desiredStateList) {
            if (desiredStateList.isEmpty() || desiredStateList.equals(Collections.singletonList(State.FINISHED))) {
                setDesiredState(State.RUNNING);
                return;
            }
            State lastState = desiredStateList.get(desiredStateList.size() - 1);
            if (lastState == State.RUNNING) {
                return;
            } else if (lastState == State.FINISHED) {
                desiredStateList.set(desiredStateList.size() - 1, State.RUNNING);
            } else {
                desiredStateList.add(State.RUNNING);
            }
        }
    }

    /**
     * ReInstall Service.
     */
    final void requestReinstall() {
        // Ignore reinstall requests if the service is closed
        if (isClosed.get()) {
            return;
        }
        synchronized (desiredStateList) {
            setDesiredState(State.NEW, State.RUNNING);
        }
    }

    /**
     * Restart Service.
     */
    final void requestRestart() {
        // Ignore restart requests if the service is closed
        if (isClosed.get()) {
            return;
        }
        synchronized (desiredStateList) {
            // don't override in the case of re-install
            int index = desiredStateList.indexOf(State.NEW);
            if (index == -1) {
                setDesiredState(State.INSTALLED, State.RUNNING);
                return;
            }
            desiredStateList.subList(index + 1, desiredStateList.size()).clear();
            desiredStateList.add(State.RUNNING);
        }
    }

    /**
     * Stop Service.
     */
    final void requestStop() {
        synchronized (desiredStateList) {
            // don't override in the case of re-install
            int index = desiredStateList.indexOf(State.NEW);
            if (index == -1) {
                setDesiredState(State.FINISHED);
                return;
            }
            desiredStateList.subList(index + 1, desiredStateList.size()).clear();
            desiredStateList.add(State.FINISHED);
        }
    }

    private Integer getTimeoutConfigValue(String nameSpace, Integer defaultValue) {
        return Coerce.toInt(evergreenService.getConfig().findOrDefault(defaultValue,
                EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, nameSpace, TIMEOUT_NAMESPACE_TOPIC));
    }

    private int getErrorResetTime() {
        return Coerce.toInt(evergreenService.getConfig().findOrDefault(DEFAULT_ERROR_RESET_TIME_IN_SEC,
                EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, ERROR_RESET_TIME_TOPIC));
    }
}
