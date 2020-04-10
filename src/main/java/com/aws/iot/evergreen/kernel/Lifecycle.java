/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.logging.api.Logger;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

@SuppressFBWarnings(value = "JLM_JSR166_UTILCONCURRENT_MONITORENTER",
        justification = "We're synchronizing on the desired state list which is fine")
public class Lifecycle {
    public static final String LIFECYCLE_INSTALL_NAMESPACE_TOPIC = "install";
    public static final String LIFECYCLE_STARTUP_NAMESPACE_TOPIC = "startup";
    public static final String TIMEOUT_NAMESPACE_TOPIC = "timeout";

    private static final Integer DEFAULT_INSTALL_STAGE_TIMEOUT_IN_SEC = 120;
    private static final Integer DEFAULT_STARTUP_STAGE_TIMEOUT_IN_SEC = 120;
    private static final String CURRENT_STATE_METRIC_NAME = "currentState";
    private static final String INVALID_STATE_ERROR_EVENT = "service-invalid-state-error";
    // The maximum number of ERRORED before transitioning the service state to BROKEN.
    private static final int MAXIMUM_CONTINUAL_ERROR = 3;

    private final EvergreenService evergreenService;
    private final Topic stateTopic;
    private final Logger logger;
    private Future backingTask = CompletableFuture.completedFuture(null);
    private String backingTaskName;
    private State prevState;
    @Getter
    private Future<?> lifecycleFuture;
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
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    // The number of continual occurrences from a state to ERRORED.
    // This is not thread safe and should only be used inside reportState().
    private final Map<State, Integer> stateToErroredCount = new HashMap<>();
    // We only need to track the ERROR for the state transition starting from NEW, INSTALLED and RUNNING because
    // these states impact whether the service can function as expected.
    private static final Set<State> STATES_TO_ERRORED = new HashSet<>(Arrays.asList(State.NEW, State.INSTALLED,
            State.RUNNING));

    /**
     * Constructor for lifecycle.
     *
     * @param evergreenService service that this is the lifecycle for
     * @param state            service's state topic
     * @param logger           service's logger
     */
    public Lifecycle(EvergreenService evergreenService, Topic state, Logger logger) {
        this.evergreenService = evergreenService;
        this.prevState = State.NEW;
        this.stateTopic = state;
        this.logger = logger;
    }

    private void updateStateAndBroadcast(State newState) {
        final State currentState = evergreenService.getState();

        if (newState.equals(currentState)) {
            return;
        }

        // TODO: Add validation

        // Sync on State.class to make sure the order of setValue and globalNotifyStateChanged are consistent
        // across different services.
        synchronized (State.class) {
            prevState = currentState;
            stateTopic.withValue(newState);
            evergreenService.getContext().globalNotifyStateChanged(evergreenService, prevState, newState);
        }
        logger.atInfo().setEventType("service-set-state").kv(CURRENT_STATE_METRIC_NAME, currentState)
                .kv("newState", newState).log();
    }

    /**
     * public API for service to report state. Allowed state are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's
     *                 actual state
     */
    synchronized void reportState(State newState) {
        logger.atInfo().setEventType("service-report-state").kv("newState", newState).log();
        if (!ALLOWED_STATES_FOR_REPORTING.contains(newState)) {
            logger.atError().setEventType(INVALID_STATE_ERROR_EVENT).kv("newState", newState)
                    .log("Invalid report state");
        }
        // TODO: Add more validations

        if (evergreenService.getState().equals(State.INSTALLED) && newState.equals(State.FINISHED)) {
            // if a service doesn't have any run logic, request stop on service to clean up DesiredStateList
            requestStop();
        }

        State currentState = evergreenService.getState();

        if (State.ERRORED.equals(newState) && STATES_TO_ERRORED.contains(currentState)) {
            // If the reported state is ERRORED, we'll increase the ERROR counter for the current state.
            stateToErroredCount.compute(currentState, (k, v) -> (v == null) ? 1 : v + 1);
        } else {
            // If the reported state is a non-ERRORED state, we would like to reset the ERROR counter for the current
            // state. This is to avoid putting the service to BROKEN state because of transient issues.
            stateToErroredCount.put(currentState, 0);
        }
        if (stateToErroredCount.get(currentState) > MAXIMUM_CONTINUAL_ERROR) {
            enqueueStateEvent(State.BROKEN);
        } else {
            enqueueStateEvent(newState);
        }
    }

    private Optional<State> getReportState() {
        Object top = stateEventQueue.poll();
        if (top instanceof State) {
            return Optional.of((State) top);
        }
        return Optional.empty();
    }

    /**
     * Returns true if the service has reached its desired state.
     *
     * @return
     */
    public boolean reachedDesiredState() {
        synchronized (desiredStateList) {
            return desiredStateList.isEmpty()
                    // when reachedDesiredState() is called in global state listener,
                    // service lifecycle thread hasn't drained the desiredStateList yet.
                    // Therefore adding this check.
                    || desiredStateList.stream().allMatch(s -> s == evergreenService.getState());
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

    void setDesiredState(State... state) {
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

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
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

    void startStateTransition() throws InterruptedException {
        while (!(isClosed.get() && evergreenService.getState().isClosable())) {
            Optional<State> desiredState;
            State current = evergreenService.getState();
            logger.atInfo().setEventType("service-state-transition-start")
                    .kv(CURRENT_STATE_METRIC_NAME, current).log();

            // if already in desired state, remove the head of desired state list.
            desiredState = peekOrRemoveFirstDesiredState(current);
            while (desiredState.isPresent() && desiredState.get().equals(current)) {
                desiredState = peekOrRemoveFirstDesiredState(current);
            }
            AtomicReference<Future> triggerTimeOutReference = new AtomicReference<>();
            switch (current) {
                case BROKEN:
                    if (handleCurrentStateBroken(desiredState)) {
                        break;
                    }
                    continue;
                case NEW:
                    if (handleCurrentStateNew(desiredState)) {
                        break;
                    }
                    continue;
                case INSTALLED:
                    if (handleCurrentStateInstalled(desiredState, triggerTimeOutReference)) {
                        break;
                    }
                    continue;
                case RUNNING:
                    if (handleCurrentStateRunning(desiredState)) {
                        break;
                    }
                    continue;
                case STOPPING:
                    handleCurrentStateStopping();
                    continue;
                case FINISHED:
                    if (handleCurrentStateFinished(desiredState)) {
                        break;
                    }
                    continue;
                case ERRORED:
                    handleCurrentStateErrored(desiredState);
                    continue;
                default:
                    logger.atError(INVALID_STATE_ERROR_EVENT)
                            .kv(CURRENT_STATE_METRIC_NAME, evergreenService.getState())
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
            // service transitioning to another state, cancelling task monitoring the timeout for startup
            Future triggerTimeOutFuture = triggerTimeOutReference.get();
            if (triggerTimeOutFuture != null) {
                triggerTimeOutFuture.cancel(true);
            }
        }
    }

    private boolean handleCurrentStateBroken(Optional<State> desiredState) {
        if (!desiredState.isPresent()) {
            return true;
        }
        // Having State.NEW as the desired state indicates the service is requested to reinstall, so here
        // we'll transition out of BROKEN state to give it a new chance.
        if (State.NEW.equals(desiredState.get())) {
            updateStateAndBroadcast(State.NEW);
        } else {
            logger.atError("service-broken").log("service is broken. Deployment is needed");
            return true;
        }
        return false;
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private boolean handleCurrentStateNew(Optional<State> desiredState) throws InterruptedException {
        // if no desired state is set, don't do anything.
        if (!desiredState.isPresent()) {
            return true;
        }
        CountDownLatch installLatch = new CountDownLatch(1);
        setBackingTask(() -> {
            try {
                evergreenService.install();
            } catch (InterruptedException t) {
                logger.atWarn("service-install-interrupted").log("Service interrupted while running install");
            } catch (Throwable t) {
                reportState(State.ERRORED);
                logger.atError().setEventType("service-install-error").setCause(t).log();
            } finally {
                installLatch.countDown();
            }
        }, "install");

        Topic installTimeOutTopic = evergreenService.config.find(EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                LIFECYCLE_INSTALL_NAMESPACE_TOPIC, TIMEOUT_NAMESPACE_TOPIC);
        Integer installTimeOut = installTimeOutTopic == null ? DEFAULT_INSTALL_STAGE_TIMEOUT_IN_SEC
                : (Integer) installTimeOutTopic.getOnce();
        boolean ok = installLatch.await(installTimeOut, TimeUnit.SECONDS);
        State reportState = getReportState().orElse(null);
        if (State.ERRORED.equals(reportState) || !ok) {
            updateStateAndBroadcast(State.ERRORED);
        } else if (State.BROKEN.equals(reportState)) {
            updateStateAndBroadcast(State.BROKEN);
        } else {
            updateStateAndBroadcast(State.INSTALLED);
        }
        return false;
    }

    private boolean handleCurrentStateInstalled(Optional<State> desiredState,
                                                AtomicReference<Future> triggerTimeOutReference) {
        stopBackingTask();
        if (!desiredState.isPresent()) {
            return true;
        }

        switch (desiredState.get()) {
            case FINISHED:
                updateStateAndBroadcast(State.FINISHED);
                return false;
            case NEW:
                // This happens if a restart is requested while we're currently INSTALLED
                updateStateAndBroadcast(State.NEW);
                return false;
            case RUNNING:
                handleStateTransitionInstalledToRunning(triggerTimeOutReference);
                break;
            default:
                // not allowed for NEW, STOPPING, ERRORED, BROKEN
                logger.atError().setEventType(INVALID_STATE_ERROR_EVENT).kv("desiredState", desiredState)
                        .log("Unexpected desired state");
                break;
        }
        return true;
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private void handleStateTransitionInstalledToRunning(AtomicReference<Future> triggerTimeOutReference) {
        setBackingTask(() -> {
            try {
                logger.atInfo().setEventType("service-awaiting-start").log("waiting for dependencies to start");
                evergreenService.waitForDependencyReady();
                logger.atInfo().setEventType("service-starting").log();
            } catch (InterruptedException e) {
                logger.atWarn().setEventType("service-dependency-error")
                        .log("Got interrupted while waiting for dependency ready");
                return;
            }
            try {
                Topics startupTopics = evergreenService.config
                        .findTopics(EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                                LIFECYCLE_STARTUP_NAMESPACE_TOPIC);
                // only schedule task to report error for services with startup stage
                // timeout for run stage is handled in generic external service
                if (startupTopics != null) {
                    Topic timeOutTopic = startupTopics.findLeafChild(TIMEOUT_NAMESPACE_TOPIC);
                    // default time out is 120 seconds
                    Integer timeout = timeOutTopic == null ? DEFAULT_STARTUP_STAGE_TIMEOUT_IN_SEC
                            : (Integer) timeOutTopic.getOnce();


                    Future<?> schedule =
                            evergreenService.getContext().get(ScheduledExecutorService.class).schedule(() -> {
                                if (!State.RUNNING.equals(evergreenService.getState())) {
                                    logger.atWarn("service-startup-timed-out")
                                            .log("Service failed to startup within timeout");
                                    reportState(State.ERRORED);
                                }
                            }, timeout, TimeUnit.SECONDS);
                    triggerTimeOutReference.set(schedule);
                }
                // TODO: rename to  initiateStartup. Service need to report state to RUNNING.
                evergreenService.startup();
            } catch (InterruptedException i) {
                logger.atWarn("service-run-interrupted").log("Service interrupted while running startup");
            } catch (Throwable t) {
                reportState(State.ERRORED);
                logger.atError().setEventType("service-runtime-error").setCause(t).log();
            }
        }, "start");
    }

    private boolean handleCurrentStateRunning(Optional<State> desiredState) {
        if (!desiredState.isPresent()) {
            return true;
        }
        // desired state is different, let's transition to stopping state first.
        updateStateAndBroadcast(State.STOPPING);
        return false;
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private void handleCurrentStateStopping() throws InterruptedException {
        // does not handle desiredState in STOPPING because we must stop first.
        // does not use setBackingTask because it will cancel the existing task.
        CountDownLatch stopping = new CountDownLatch(1);
        Future<?> shutdownFuture = evergreenService.getContext().get(ExecutorService.class).submit(() -> {
            try {
                evergreenService.shutdown();
            } catch (InterruptedException i) {
                logger.atWarn("service-shutdown-interrupted").log("Service interrupted while running shutdown");
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
        } else {
            Optional<State> desiredState = peekOrRemoveFirstDesiredState(State.FINISHED);
            serviceTerminatedMoveToDesiredState(desiredState.orElse(State.FINISHED));
        }
    }

    private boolean handleCurrentStateFinished(Optional<State> desiredState) {
        if (!desiredState.isPresent()) {
            return true;
        }

        logger.atInfo().setEventType("service-state-transition")
                .kv(CURRENT_STATE_METRIC_NAME, evergreenService.getState())
                .kv("desiredState", desiredState).log();
        serviceTerminatedMoveToDesiredState(desiredState.get());
        return false;
    }

    private void handleCurrentStateErrored(Optional<State> desiredState) throws InterruptedException {
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
            case RUNNING:
                updateStateAndBroadcast(State.STOPPING);
                break;
            case NEW: // error in installing.
                updateStateAndBroadcast(State.NEW);
                break;
            case INSTALLED: // error in starting
                updateStateAndBroadcast(State.INSTALLED);
                break;
            case STOPPING:
                // not handled;
                desiredState = peekOrRemoveFirstDesiredState(State.FINISHED);
                serviceTerminatedMoveToDesiredState(desiredState.orElse(State.FINISHED));
                break;
            default:
                logger.atError().setEventType(INVALID_STATE_ERROR_EVENT).kv("previousState", prevState)
                        .log("Unexpected previous state");
                updateStateAndBroadcast(State.FINISHED);
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
        switch (desiredState) {
            case NEW:
                updateStateAndBroadcast(State.NEW);
                break;
            case INSTALLED:
            case RUNNING:
                updateStateAndBroadcast(State.INSTALLED);
                break;
            case FINISHED:
                updateStateAndBroadcast(State.FINISHED);
                break;
            default:
                // not allowed to set desired state to STOPPING, ERRORED, BROKEN
                logger.atError().setEventType(INVALID_STATE_ERROR_EVENT).addKeyValue("desiredState", desiredState)
                        .log("Unexpected desired state");
        }
    }

    private synchronized void setBackingTask(Runnable r, String action) {
        Future bt = backingTask;
        String btName = backingTaskName;

        if (!bt.isDone()) {
            backingTask = CompletableFuture.completedFuture(null);
            logger.info("Stopping backingTask {}", btName);
            bt.cancel(true);
        }

        if (r != null) {
            backingTaskName = action;
            logger.debug("Scheduling backingTask {}", backingTaskName);
            backingTask = evergreenService.getContext().get(ExecutorService.class).submit(r);
        }
    }

    private void stopBackingTask() {
        setBackingTask(null, null);
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    void initLifecycleThread() {
        lifecycleFuture = evergreenService.getContext().get(ExecutorService.class).submit(() -> {
            while (!isClosed.get()) {
                try {
                    startStateTransition();
                    return;
                } catch (InterruptedException i) {
                    logger.atWarn().setEventType("service-state-transition-interrupted")
                            .log("Service lifecycle thread interrupted. Thread will exit now");
                    return;
                } catch (Throwable e) {
                    logger.atError().setEventType("service-state-transition-error")
                            .kv(CURRENT_STATE_METRIC_NAME, evergreenService.getState()).setCause(e)
                            .log();
                    logger.atInfo().setEventType("service-state-transition-retry")
                            .kv(CURRENT_STATE_METRIC_NAME, evergreenService.getState()).log();
                }
            }
        });
    }

    void setClosed(boolean b) {
        isClosed.set(b);
    }

    /**
     * Start Service.
     */
    final void requestStart() {
        synchronized (desiredStateList) {
            if (desiredStateList.isEmpty()) {
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
        synchronized (desiredStateList) {
            setDesiredState(State.NEW, State.RUNNING);
        }
    }

    /**
     * Restart Service.
     */
    final void requestRestart() {
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
}
