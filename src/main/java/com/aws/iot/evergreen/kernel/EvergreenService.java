/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

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
import lombok.AllArgsConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.util.Utils.getUltimateCause;

public class EvergreenService implements InjectionActions {
    public static final String STATE_TOPIC_NAME = "_State";
    public static final String SERVICES_NAMESPACE_TOPIC = "services";
    public static final String SERVICE_LIFECYCLE_NAMESPACE_TOPIC = "lifecycle";
    public static final String SERVICE_NAME_KEY = "serviceName";

    private static final String CURRENT_STATE_METRIC_NAME = "currentState";

    protected final Topics config;
    public Context context;

    private final Topic state;
    private final Lifecycle lifecycle;
    private final Object dependersExitedLock = new Object();
    private Throwable error;
    private final Periodicity periodicityInformation;
    private final Object dependencyReadyLock = new Object();

    // dependencies that are explicitly declared by customer in config store.
    private final Topic externalDependenciesTopic;
    // Services that this service depends on.
    // Includes both explicit declared dependencies and implicit ones added through 'autoStart' and @Inject annotation.
    protected final ConcurrentHashMap<EvergreenService, DependencyInfo> dependencies = new ConcurrentHashMap<>();
    // Service logger instance
    protected final Logger logger;


    /**
     * Constructor for EvergreenService.
     *
     * @param topics root Configuration topic for this service
     */
    public EvergreenService(Topics topics) {
        this.config = topics;
        this.context = topics.getContext();

        // TODO: Validate syntax for lifecycle keywords and fail early
        // skipif will require validation for onpath/exists etc. keywords

        this.logger = LogManager.getLogger(getName());
        logger.dfltKv(SERVICE_NAME_KEY, getName());
        logger.dfltKv(CURRENT_STATE_METRIC_NAME, (Supplier<State>) this::getState);
        this.state = initStateTopic(topics);

        this.externalDependenciesTopic = topics.createLeafChild("dependencies").dflt(new ArrayList<String>());
        this.externalDependenciesTopic.withParentNeedsToKnow(false);
        this.lifecycle = new Lifecycle(this, state, logger);

        initDependenciesTopic();
        periodicityInformation = Periodicity.of(this);
    }

    @Override
    public void postInject() {
        // !IMPORTANT!
        // Only start the lifecycle thread here and NOT in the constructor.
        // Java construction order means that there would be a race between starting the lifecycle
        // thread and the subclasses instance fields being set. This leads to very difficult to debug
        // problems. Since postInject() only runs after construction, it is safe to start the lifecycle
        // thread here.
        // See Java Language Spec for more https://docs.oracle.com/javase/specs/jls/se8/html/jls-12.html#jls-12.5
        lifecycle.initLifecycleThread();
    }

    public State getState() {
        return (State) state.getOnce();
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
        Optional<State> reportedState = lifecycle.lastReportedState();
        return reportedState.isPresent() && reportedState.get().equals(state);
    }

    public long getStateModTime() {
        return state.getModtime();
    }

    /**
     * public API for service to report state. Allowed state are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's
     *                 actual state
     */
    public synchronized void reportState(State newState) {
        lifecycle.reportState(newState);
    }

    private Topic initStateTopic(final Topics topics) {
        Topic state = topics.createLeafChild(STATE_TOPIC_NAME);
        state.withParentNeedsToKnow(false);
        state.withValue(State.NEW);
        state.validate((newStateObj, oldStateObj) -> {
            State newState = Coerce.toEnum(State.class, newStateObj);
            return newState == null ? oldStateObj : newStateObj;
        });

        return state;
    }

    private synchronized void initDependenciesTopic() {
        externalDependenciesTopic.subscribe((what, node) -> {
            if (!WhatHappened.changed.equals(what) || node.getModtime() <= 1) {
                return;
            }
            Iterable<String> depList = (Iterable<String>) node.getOnce();
            logger.atInfo().log("Setting up dependencies again {}", String.join(",", depList));
            try {
                setupDependencies(depList);
            } catch (ServiceLoadException | InputValidationException e) {
                logger.atError().log("Error while setting up dependencies from subscription", e);
            }
        });

        try {
            setupDependencies((Iterable<String>) externalDependenciesTopic.getOnce());
        } catch (ServiceLoadException | InputValidationException e) {
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
     *
     * @return
     */
    public boolean reachedDesiredState() {
        return lifecycle.reachedDesiredState();
    }

    /**
     * Start Service.
     */
    public final void requestStart() {
        lifecycle.requestStart();
    }

    /**
     * ReInstall Service.
     */
    public final void requestReinstall() {
        lifecycle.requestReinstall();
    }

    /**
     * Restart Service.
     */
    public final void requestRestart() {
        lifecycle.requestRestart();
    }

    /**
     * Stop Service.
     */
    public final void requestStop() {
        lifecycle.requestStop();
    }

    /**
     * Custom handler to handle error.
     *
     * @throws InterruptedException if the thread is interrupted while handling the error
     */
    public void handleError() throws InterruptedException {
    }

    /**
     * Report that the service has hit an error.
     *
     * @param e Throwable issue that caused the error
     */
    public void serviceErrored(Throwable e) {
        e = getUltimateCause(e);
        error = e;
        logger.atError("service-errored", e).log();
        reportState(State.ERRORED);
    }

    public void serviceErrored(String reason) {
        logger.atError("service-errored").kv("reason", reason).log();
        reportState(State.ERRORED);
    }

    public boolean isErrored() {
        return !(getState().isHappy() && error == null);
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
        lifecycle.reportState(State.RUNNING);
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
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public final CompletableFuture<Void> close() {
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
                lifecycle.setClosed(true);
                requestStop();
                lifecycle.getLifecycleFuture().get();
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
     * @param startWhen                 the state that the dependent service must be in before starting the current
     *                                  service.
     * @param isDefault                 True if the dependency is added without explicit declaration
     *                                  in 'dependencies' Topic.
     * @throws InputValidationException if the provided arguments are invalid.
     */
    public synchronized void addOrUpdateDependency(EvergreenService dependentEvergreenService, State startWhen,
                                                   boolean isDefault) throws InputValidationException {
        if (dependentEvergreenService == null || startWhen == null) {
            throw new InputValidationException("One or more parameters was null");
        }

        dependencies.compute(dependentEvergreenService, (dependentService, dependencyInfo) -> {
            // If the dependency already exists, we should first remove the subscriber before creating the
            // new subscriber with updated input.
            if (dependencyInfo != null) {
                dependentEvergreenService.getStateTopic().remove(dependencyInfo.stateTopicSubscriber);
            }
            Subscriber subscriber = createDependencySubscriber(dependentEvergreenService, startWhen);
            dependentEvergreenService.getStateTopic().subscribe(subscriber);
            context.get(Kernel.class).clearODcache();
            return new DependencyInfo(startWhen, isDefault, subscriber);
        });
    }

    private Subscriber createDependencySubscriber(EvergreenService dependentEvergreenService, State startWhenState) {
        return (WhatHappened what, Topic t) -> {
            if ((State.INSTALLED.equals(getState()) || State.RUNNING.equals(getState()))
                    && !dependencyReady(dependentEvergreenService, startWhenState)) {
                requestRestart();
                logger.atInfo("service-restart").log("Restarting service because dependency {} was in a bad state",
                        dependentEvergreenService.getName());
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
                logger.atDebug("service-waiting-for-depender-to-finish").log();
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
            logger.atDebug("continue-waiting-for-dependencies").kv("waitingFor", dependerService.get().getName()).log();
            return false;
        }
        return true;
    }

    private boolean dependencyReady() {
        List<EvergreenService> ret =
                dependencies.entrySet()
                        .stream()
                        .filter(e -> !dependencyReady(e.getKey(), e.getValue().startWhen))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
        if (!ret.isEmpty()) {
            logger.atDebug("continue-waiting-for-dependencies").kv("waitingFor", ret).log();
        }
        return ret.isEmpty();
    }

    private boolean dependencyReady(EvergreenService v, State startWhenState) {
        State state = v.getState();
        return state.isHappy() && (startWhenState == null || startWhenState.preceedsOrEqual(state));
    }

    void waitForDependencyReady() throws InterruptedException {
        synchronized (dependencyReadyLock) {
            while (!dependencyReady()) {
                logger.atDebug("service-waiting-for-dependency").log();
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

    public Topics getServiceConfig() {
        return config;
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

        EvergreenService d = context.get(Kernel.class).locate(name);
        return new Pair<>(d, x == null ? State.RUNNING : x);
    }

    private synchronized void setupDependencies(Iterable<String> dependencyList)
            throws ServiceLoadException, InputValidationException {
        Map<EvergreenService, State> oldDependencies = new HashMap<>(getDependencies());
        Map<EvergreenService, State> keptDependencies = getDependencyStateMap(dependencyList);

        Set<EvergreenService> removedDependencies = dependencies.entrySet().stream()
                .filter(e -> !keptDependencies.containsKey(e.getKey()) && !e.getValue().isDefaultDependency)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (!removedDependencies.isEmpty()) {
            logger.atInfo("removing-unused-dependencies").kv("removedDependencies", removedDependencies).log();

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
                logger.atWarn("add-dependency")
                        .log("Unable to add dependency {}", dependentEvergreenService, e);
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

    protected void putDependenciesIntoSet(Set<EvergreenService> deps) {
        deps.add(this);
        dependencies.keySet().forEach(d -> {
            if (!deps.contains(d)) {
                d.putDependenciesIntoSet(deps);
            }
        });
    }

    //TODO: return the entire dependency info
    public Map<EvergreenService, State> getDependencies() {
        return dependencies.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().startWhen));
    }

    protected final long getStateGeneration() {
        return lifecycle.getStateGeneration().get();
    }

    protected Topics getLifecycleTopic() {
        return config.findInteriorChild(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
    }

    public enum RunStatus {
        OK, NothingDone, Errored
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
