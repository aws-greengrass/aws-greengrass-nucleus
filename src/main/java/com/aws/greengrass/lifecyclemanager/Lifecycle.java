/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ComponentStatusCode;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.status.model.ComponentStatusDetails;
import com.aws.greengrass.util.Coerce;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
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
    public static final String LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC = "bootstrap";
    public static final String LIFECYCLE_INSTALL_NAMESPACE_TOPIC = "install";
    public static final String LIFECYCLE_STARTUP_NAMESPACE_TOPIC = "startup";
    public static final String LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC = "shutdown";
    public static final String LIFECYCLE_RECOVER_NAMESPACE_TOPIC = "recover";
    public static final String TIMEOUT_NAMESPACE_TOPIC = "timeout";
    public static final String ERROR_RESET_TIME_TOPIC = "errorResetTime";
    public static final String REQUIRES_PRIVILEGE_NAMESPACE_TOPIC = "requiresPrivilege";

    // Note: topics with underscore-prefixed names are maintained in memory only, i.e. excluded from disk writes.
    public static final String STATE_TOPIC_NAME = "_State";
    public static final String STATUS_CODE_TOPIC_NAME = "_StatusCode";
    public static final String STATUS_REASON_TOPIC_NAME = "_StatusReason";
    private static final String NEW_STATE_METRIC_NAME = "newState";

    private static final Integer DEFAULT_INSTALL_STAGE_TIMEOUT_IN_SEC = 120;
    private static final Integer DEFAULT_STARTUP_STAGE_TIMEOUT_IN_SEC = 120;
    private static final Integer DEFAULT_SHUTDOWN_STAGE_TIMEOUT_IN_SEC = 15;
    public static final Integer DEFAULT_ERROR_RECOVERY_HANDLER_TIMEOUT_SEC = 60;
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
    private final GreengrassService greengrassService;


    // lastReportedState stores the last reported state (not necessarily processed)
    private final AtomicReference<State> lastReportedState = new AtomicReference<>();
    private final Topic stateTopic;
    private final Topic statusCodeTopic;
    private final Topic statusReasonTopic;
    private final Logger logger;
    private final AtomicReference<Future> backingTask = new AtomicReference<>(CompletableFuture.completedFuture(null));
    private String backingTaskName;

    private Future<?> lifecycleThread;
    // A state event can be a reported state event, or a desired state updated notification.
    private final BlockingQueue<StateEvent> stateEventQueue = new LinkedBlockingQueue<>();
    // DesiredStateList is used to set desired path of state transition.
    // Eg. Start a service will need DesiredStateList to be <RUNNING>
    // ReInstall a service will set DesiredStateList to <FINISHED->NEW->RUNNING>
    private final List<State> desiredStateList = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    private static final Map<State, Collection<State>> ALLOWED_STATE_TRANSITION_FOR_REPORTING =
            new EnumMap<>(State.class);
    // The number of continual occurrences from a state to ERRORED.
    // This is not thread safe and should only be used inside reportState().
    private final Map<State, List<Long>> stateToErroredCount = new EnumMap<>(State.class);
    // We only need to track the ERROR from these states because
    // they impact whether the service can function as expected.
    private static final Set<State> STATES_TO_ERRORED =
            new HashSet<>(Arrays.asList(State.NEW, State.STARTING, State.RUNNING));

    static {
        ALLOWED_STATE_TRANSITION_FOR_REPORTING.put(State.NEW, Collections.singletonList(State.ERRORED));
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
     * @param greengrassService service that this is the lifecycle for
     * @param logger           service's logger
     * @param topics           config namespace for storing the state topic
     */
    public Lifecycle(GreengrassService greengrassService, Logger logger, Topics topics) {
        this.greengrassService = greengrassService;
        this.stateTopic = initStateTopic(topics);
        this.statusCodeTopic =
                initTopic(topics, STATUS_CODE_TOPIC_NAME).withValue(Arrays.asList(ComponentStatusCode.NONE.name()));
        this.statusReasonTopic =
                initTopic(topics, STATUS_REASON_TOPIC_NAME).withValue(ComponentStatusCode.NONE.getDescription());
        this.logger = logger;
    }

    private State getLastReportedState() {
        State lastState = lastReportedState.get();
        if (lastState == null) {
            lastState = getState();
        }
        return lastState;
    }

    synchronized void reportState(State newState) {
        reportState(newState, null, null, null);
    }

    synchronized void reportState(State newState, ComponentStatusCode statusCode) {
        reportState(newState, statusCode, null, null);
    }

    synchronized void reportState(State newState, ComponentStatusCode statusCode, Integer exitCode) {
        reportState(newState, statusCode, exitCode, null);
    }

    synchronized void reportState(State newState, ComponentStatusCode statusCode, Integer exitCode,
                                  String statusReason) {
        Collection<State> allowedStatesForReporting =
                ALLOWED_STATE_TRANSITION_FOR_REPORTING.get(getLastReportedState());
        if (allowedStatesForReporting == null || !allowedStatesForReporting.contains(newState)) {
            logger.atWarn(INVALID_STATE_ERROR_EVENT).kv(NEW_STATE_METRIC_NAME, newState).log("Invalid reported state");
            return;
        }

        if (statusCode == null) {
            statusCode = ComponentStatusCode.getDefaultStatusCodeForTransition(getLastReportedState(), newState);
        }
        if (statusReason == null) {
            if (exitCode == null) {
                statusReason = statusCode.getDescription();
            } else {
                statusReason = statusCode.getDescriptionWithExitCode(exitCode);
            }
        }

        internalReportState(newState, statusCode, statusReason);
    }

    private synchronized void internalReportState(State newState) {
        internalReportState(newState, ComponentStatusCode.NONE, ComponentStatusCode.NONE.getDescription());
    }

    private synchronized void internalReportState(State newState, ComponentStatusCode statusCode, String statusReason) {
        logger.atDebug("service-report-state").kv(NEW_STATE_METRIC_NAME, newState).log();
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

                final long now = greengrassService.getContext().get(Clock.class).millis();
                if (!v.isEmpty() && now - v.get(v.size() - 1) >= getErrorResetTime() * 1000L) {
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
            enqueueStateEvent(StateTransitionEvent.builder()
                    .newState(State.BROKEN)
                    .statusCode(statusCode)
                    .statusReason(statusReason)
                    .build());
        } else {
            enqueueStateEvent(StateTransitionEvent.builder()
                    .newState(newState)
                    .statusCode(statusCode)
                    .statusReason(statusReason)
                    .build());
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
        return State.values()[Coerce.toInt(stateTopic)];
    }

    protected ComponentStatusDetails getStatusDetails() {
        return ComponentStatusDetails.builder()
                .statusCodes(Coerce.toStringList(statusCodeTopic))
                .statusReason(Coerce.toString(statusReasonTopic))
                .build();
    }

    protected Topic getStateTopic()  {
        return stateTopic;
    }

    private Topic initStateTopic(final Topics topics) {
        return initTopic(topics, STATE_TOPIC_NAME).withValue(State.NEW.ordinal());
    }

    private Topic initTopic(final Topics topics, final String topicName) {
        Topic topic = topics.createLeafChild(topicName);
        topic.withParentNeedsToKnow(false);
        return topic;
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
            enqueueStateEvent(new DesiredStateUpdatedEvent());
        }
    }

    private void enqueueStateEvent(StateEvent event) {
        if (!stateEventQueue.offer(event)) {
            logger.atError().kv("event", event).log("couldn't put the new event to stateEventQueue");
        }
    }

    private void startStateTransition() throws InterruptedException {
        AtomicReference<Predicate<Object>> asyncFinishAction = new AtomicReference<>((stateEvent) -> true);
        State prevState = getState();
        while (!(isClosed.get() && getState().isClosable())) {
            Optional<State> desiredState;
            State current = getState();
            logger.atDebug("service-state-transition-start").log();

            Configuration kernelConfig = greengrassService.getContext().get(Configuration.class);
            // postpone start/install when configuration is under update.
            if (current == State.NEW || current == State.INSTALLED) {
                kernelConfig.waitConfigUpdateComplete();
            }

            // if already in desired state, remove the head of desired state list.
            desiredState = peekOrRemoveFirstDesiredState(current);
            while (desiredState.isPresent() && desiredState.get().equals(current)) {
                desiredState = peekOrRemoveFirstDesiredState(current);
            }

            switch (current) {
                case BROKEN:
                    handleCurrentStateBroken(desiredState, prevState);
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
                StateEvent stateEvent = stateEventQueue.poll();

                // If there are accumulated DesiredStateUpdatedEvent in the queue,
                // drain them until a StateTransitionEvent event is encountered.
                while (!(stateEvent instanceof StateTransitionEvent) && !stateEventQueue.isEmpty()) {
                    stateEvent = stateEventQueue.poll();
                }

                // if there are no events in the queue, block until one is available.
                if (stateEvent == null) {
                    stateEvent = stateEventQueue.take();
                }

                if (stateEvent instanceof StateTransitionEvent) {
                    State newState = ((StateTransitionEvent) stateEvent).getNewState();
                    if (newState == current) {
                        continue;
                    }

                    canFinish = true;
                    setState(current, (StateTransitionEvent) stateEvent);
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
     * @param stateTransitionEvent new state to transition into
     */
    void setState(State current, StateTransitionEvent stateTransitionEvent) {
        final State newState = stateTransitionEvent.getNewState();
        logger.atInfo("service-set-state").kv(NEW_STATE_METRIC_NAME, newState).log();
        // Sync on State.class to make sure the order of setValue and globalNotifyStateChanged
        // are consistent across different services.
        synchronized (State.class) {
            stateTopic.withValue(newState.ordinal());
            statusCodeTopic.withValue(stateTransitionEvent.getStatusCode().name());
            statusReasonTopic.withValue(stateTransitionEvent.getStatusReason());
            greengrassService.getContext().globalNotifyStateChanged(greengrassService, current, newState);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private void handleCurrentStateBroken(Optional<State> desiredState, State previousState)
            throws InterruptedException {
        switch (previousState) {
            case STARTING:
            case RUNNING:
            case ERRORED: // shouldn't happen. Try to stop the service anyways.
                logger.atInfo("Stopping service in BROKEN state");
                Future<?> shutdownFuture = greengrassService.getContext().get(ExecutorService.class).submit(() -> {
                    try {
                        greengrassService.shutdown();
                    } catch (InterruptedException i) {
                        logger.atWarn("service-shutdown-interrupted").log("Service interrupted while running shutdown");
                    } catch (Throwable i) {
                        logger.atError("service-shutdown-error").setCause(i).log();
                    }
                });

                try {
                    Integer timeout = getTimeoutConfigValue(
                            LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, DEFAULT_SHUTDOWN_STAGE_TIMEOUT_IN_SEC);
                    shutdownFuture.get(timeout, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    logger.atError("service-shutdown-error").setCause(e).log();
                } catch (TimeoutException te) {
                    logger.atWarn("service-shutdown-timeout").log();
                    shutdownFuture.cancel(true);
                } finally {
                    stopBackingTask();
                }
                break;
            default:
                // do nothing
        }
        if (!desiredState.isPresent()) {
            return;
        }
        // Having State.NEW as the desired state indicates the service is requested to reinstall, so here
        // we'll transition out of BROKEN state to give it a new chance.
        if (State.NEW.equals(desiredState.get())) {
            internalReportState(State.NEW);
            stateToErroredCount.clear();
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
                greengrassService.install();
            } catch (InterruptedException t) {
                logger.atWarn("service-install-interrupted").log("Service interrupted while running install");
            } catch (Throwable t) {
                greengrassService.serviceErrored(t);
            }
        }, LIFECYCLE_INSTALL_NAMESPACE_TOPIC);

        Integer installTimeOut = getTimeoutConfigValue(
                LIFECYCLE_INSTALL_NAMESPACE_TOPIC, DEFAULT_INSTALL_STAGE_TIMEOUT_IN_SEC);

        try {
            backingTask.get().get(installTimeOut, TimeUnit.SECONDS);
            if (!State.ERRORED.equals(lastReportedState.get())) {
                internalReportState(State.INSTALLED);
            }
        } catch (ExecutionException ee) {
            greengrassService.serviceErrored(ee);
        } catch (TimeoutException te) {
            greengrassService.serviceErrored(ComponentStatusCode.INSTALL_TIMEOUT, "Timeout in install");
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
                logger.atDebug("service-awaiting-start").log("waiting for dependencies to start");
                greengrassService.waitForDependencyReady();
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
        long currentStateGeneration = stateGeneration.incrementAndGet();
        Integer timeout = getTimeoutConfigValue(
                LIFECYCLE_STARTUP_NAMESPACE_TOPIC, DEFAULT_STARTUP_STAGE_TIMEOUT_IN_SEC);
        Future<?> schedule =
            greengrassService.getContext().get(ScheduledExecutorService.class).schedule(() -> {
                if (getState().equals(State.STARTING) && currentStateGeneration == getStateGeneration().get()) {
                    greengrassService.serviceErrored(ComponentStatusCode.STARTUP_TIMEOUT, "startup timeout");
                }
            }, timeout, TimeUnit.SECONDS);

        replaceBackingTask(() -> {
            try {
                if (!greengrassService.dependencyReady()) {
                    internalReportState(State.INSTALLED);
                    return;
                }
                greengrassService.startup();
            } catch (InterruptedException i) {
                logger.atWarn("service-run-interrupted").log("Service interrupted while running startup");
            } catch (Throwable t) {
                greengrassService.serviceErrored(t);
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
        Future<?> shutdownFuture = greengrassService.getContext().get(ExecutorService.class).submit(() -> {
            try {
                greengrassService.shutdown();
            } catch (InterruptedException i) {
                logger.atWarn("service-shutdown-interrupted").log("Service interrupted while running shutdown");
            } catch (Throwable t) {
                greengrassService.serviceErrored(t);
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
            greengrassService.serviceErrored(ee);
        } catch (TimeoutException te) {
            shutdownFuture.cancel(true);
            greengrassService.serviceErrored(ComponentStatusCode.SHUTDOWN_TIMEOUT, "Timeout in shutdown");
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
            greengrassService.handleError();
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
            backingTask.set(greengrassService.getContext().get(ExecutorService.class).submit(r));
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
        lifecycleThread = greengrassService.getContext().get(ExecutorService.class).submit(() -> {
            String threadName = Thread.currentThread().getName();
            try {
                Thread.currentThread().setName(greengrassService.getName() + "-lifecycle");
                while (!isClosed.get()) {
                    try {
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
            } finally {
                Thread.currentThread().setName(threadName); // reset thread name so that if the thread is recycled it
                // will not falsely claim to be a lifecycle thread.
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
     * Restart Service. Will not restart if the service has not been started once and there's no desired state.
     *
     * @return true if the request will happen, false otherwise.
     */
    final boolean requestRestart() {
        // Ignore restart requests if the service is closed
        if (isClosed.get()) {
            return false;
        }
        logger.atTrace().log("Waiting for the desired state list");
        synchronized (desiredStateList) {
            // If there are no more desired states and the service is currently new, then do not
            // restart. Only restart when the service is "RUNNING" (which includes several states)
            if (desiredStateList.isEmpty() && State.NEW.equals(getState())) {
                return false;
            }

            // don't override in the case of re-install
            int index = desiredStateList.indexOf(State.NEW);
            if (index == -1) {
                setDesiredState(State.INSTALLED, State.RUNNING);
                return true;
            }
            desiredStateList.subList(index + 1, desiredStateList.size()).clear();
            desiredStateList.add(State.RUNNING);
        }
        logger.atTrace().log("Returning true from request restart");
        return true;
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
        return Coerce.toInt(greengrassService.getConfig().findOrDefault(defaultValue,
                GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, nameSpace, TIMEOUT_NAMESPACE_TOPIC));
    }

    private int getErrorResetTime() {
        return Coerce.toInt(greengrassService.getConfig().findOrDefault(DEFAULT_ERROR_RESET_TIME_IN_SEC,
                GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, ERROR_RESET_TIME_TOPIC));
    }

    static class StateEvent {
        protected StateEvent() {
        }
    }

    static class DesiredStateUpdatedEvent extends Lifecycle.StateEvent {
    }

    @AllArgsConstructor
    @Builder
    @Data
    static class StateTransitionEvent extends Lifecycle.StateEvent {
        private State newState;
        private ComponentStatusCode statusCode;
        private String statusReason;
    }
}
