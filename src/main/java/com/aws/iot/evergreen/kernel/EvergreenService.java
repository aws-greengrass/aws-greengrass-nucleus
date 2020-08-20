/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.DependencyType;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Pair;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.util.Utils.getUltimateCause;

public class EvergreenService implements InjectionActions, DisruptableCheck {
    public static final String SERVICES_NAMESPACE_TOPIC = "services";
    public static final String RUNTIME_STORE_NAMESPACE_TOPIC = "runtime";
    public static final String PRIVATE_STORE_NAMESPACE_TOPIC = "_private";
    public static final String SERVICE_LIFECYCLE_NAMESPACE_TOPIC = "lifecycle";
    public static final String SERVICE_DEPENDENCIES_NAMESPACE_TOPIC = "dependencies";
    public static final String ACCESS_CONTROL_NAMESPACE_TOPIC = "accessControl";
    public static final String SERVICE_NAME_KEY = "serviceName";
    public static final String SETENV_CONFIG_NAMESPACE = "setenv";
    private static final String CURRENT_STATE_METRIC_NAME = "currentState";


    @Getter
    protected final Topics config;
    private final Topics privateConfig;

    //TODO: make the field private
    @Getter
    public Context context;

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
        this(topics, topics.lookupTopics(PRIVATE_STORE_NAMESPACE_TOPIC));
    }

    /**
     * Constructor for EvergreenService.
     *
     * @param topics        root Configuration topic for this service
     * @param privateConfig root configuration topic for the service's private config which must not be shared
     */
    public EvergreenService(Topics topics, Topics privateConfig) {
        this.config = topics;
        this.privateConfig = privateConfig;
        this.context = topics.getContext();

        // TODO: Validate syntax for lifecycle keywords and fail early
        // skipif will require validation for onpath/exists etc. keywords

        this.logger = LogManager.getLogger(getServiceName()).createChild();
        logger.dfltKv(SERVICE_NAME_KEY, getServiceName());
        logger.dfltKv(CURRENT_STATE_METRIC_NAME, (Supplier<State>) this::getState);

        this.externalDependenciesTopic =
                topics.createLeafChild(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC).dflt(new ArrayList<String>());
        this.externalDependenciesTopic.withParentNeedsToKnow(false);
        this.lifecycle = new Lifecycle(this, logger, privateConfig);

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
        if (lifecycle.getLifecycleThread() == null) {
            lifecycle.initLifecycleThread();
        }
    }

    public State getState() {
        return lifecycle.getState();
    }

    /**
     * Returns true if either the current or the very last reported state (if any) is equal to the provided state.
     *
     * @param state state to check against
     */
    public boolean currentOrReportedStateIs(State state) {
        return lifecycle.currentOrReportedStateIs(state);
    }

    public long getStateModTime() {
        return lifecycle.getStateTopic().getModtime();
    }

    /**
     * public API for service to report state. Allowed state are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's actual state
     */
    public void reportState(State newState) {
        lifecycle.reportState(newState);
    }


    private synchronized void initDependenciesTopic() {
        externalDependenciesTopic.subscribe((what, node) -> {
            if (!WhatHappened.changed.equals(what) || node.getModtime() <= 1) {
                return;
            }
            Collection<String> depList = (Collection<String>) node.getOnce();
            logger.atInfo().log("Setting up dependencies again {}", String.join(",", depList));
            try {
                setupDependencies(depList);
            } catch (ServiceLoadException | InputValidationException e) {
                logger.atError().log("Error while setting up dependencies from subscription", e);
            }
        });

        try {
            setupDependencies((Collection<String>) externalDependenciesTopic.getOnce());
        } catch (ServiceLoadException | InputValidationException e) {
            serviceErrored(e);
        }
    }

    public boolean inState(State s) {
        return s == getState();
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
    protected void handleError() throws InterruptedException {
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
     * Bootstrap and notify if a kernel/device restart is needed. Called when a component newly added to kernel, or the
     * version changes. Returns 0 for no-op, 100 for restarting kernel, 101 for restarting device, other code for
     * errors, and null if not configured. Refer to  {@link BootstrapSuccessCode}.
     *
     * @return exit code; 0 for no-op, 100 for restarting kernel, 101 for restarting device, other code for errors, and
     *         null if not configured. Refer to  {@link BootstrapSuccessCode}.
     * @throws InterruptedException when the execution is interrupted.
     * @throws TimeoutException     when the command execution times out.
     */
    public int bootstrap() throws InterruptedException, TimeoutException {
        return 0;
    }

    /**
     * Called when this service is known to be needed to make sure that required additional software is installed.
     *
     * @throws InterruptedException if the install task was interrupted while running
     */
    protected void install() throws InterruptedException {
    }

    /**
     * Called when all dependencies are RUNNING. If there are no dependencies, it is called right after postInject.  The
     * service doesn't transition to RUNNING until *after* this state is complete.
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
     * Called to check if the service can be disrupted to process a deployment. Default implementation returns 0,
     * meaning that the service is safe to be disrupted.
     *
     * @return Estimated time when this handler will be willing to be disrupted, expressed as milliseconds since the
     *         epoch. If the returned value is less than now (System.currentTimeMillis()) the handler is granting
     *         permission to be disrupted.  Otherwise, it will be asked again sometime later.
     */
    @Override
    public long whenIsDisruptionOK() {
        return 0;
    }

    /**
     * Called when the disruption to the service has concluded.
     */
    @Override
    public void disruptionCompleted() {
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
            logger.atInfo("service-close").log("Service is now closing");
            // removing listeners on dependencies
            dependencies.forEach((service, dependencyInfo) ->
                    service.removeStateSubscriber(dependencyInfo.stateTopicSubscriber));
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

                Future<?> fut = lifecycle.getLifecycleThread();
                if (fut != null) {
                    fut.get();
                }
                closeFuture.complete(null);
            } catch (Exception e) {
                closeFuture.completeExceptionally(e);
            }
        });
        return closeFuture;
    }

    /**
     * Add a dependency.
     *
     * @param dependentEvergreenService the service to add as a dependency.
     * @param dependencyType            type of the dependency.
     * @param isDefault                 True if the dependency is added without explicit declaration in 'dependencies'
     *                                  Topic.
     * @throws InputValidationException if the provided arguments are invalid.
     */
    public synchronized void addOrUpdateDependency(EvergreenService dependentEvergreenService,
                                                   DependencyType dependencyType, boolean isDefault)
            throws InputValidationException {
        if (dependentEvergreenService == null || dependencyType == null) {
            throw new InputValidationException("One or more parameters was null");
        }

        dependencies.compute(dependentEvergreenService, (dependentService, dependencyInfo) -> {
            // If the dependency already exists, we should first remove the subscriber before creating the
            // new subscriber with updated input.
            if (dependencyInfo != null) {
                dependentEvergreenService.removeStateSubscriber(dependencyInfo.stateTopicSubscriber);
            }
            Subscriber subscriber = createDependencySubscriber(dependentEvergreenService, dependencyType);
            dependentEvergreenService.addStateSubscriber(subscriber);
            context.get(Kernel.class).clearODcache();
            return new DependencyInfo(dependencyType, isDefault, subscriber);
        });
    }

    private Subscriber createDependencySubscriber(EvergreenService dependentEvergreenService,
                                                  DependencyType dependencyType) {
        return (WhatHappened what, Topic t) -> {
            if ((State.STARTING.equals(getState()) || State.RUNNING.equals(getState())) && !dependencyReady(
                    dependentEvergreenService, dependencyType)) {
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

    private List<EvergreenService> getHardDependers() {
        List<EvergreenService> dependers = new ArrayList<>();
        Kernel kernel = context.get(Kernel.class);
        for (EvergreenService evergreenService : kernel.orderedDependencies()) {
            for (Map.Entry<EvergreenService, DependencyInfo> entry : evergreenService.dependencies.entrySet()) {
                if (entry.getKey().equals(this) && DependencyType.HARD.equals(entry.getValue().dependencyType)) {
                    dependers.add(evergreenService);
                }
            }
        }
        return dependers;
    }

    public void addStateSubscriber(Subscriber s) {
        lifecycle.getStateTopic().subscribe(s);
    }

    public void removeStateSubscriber(Subscriber s) {
        lifecycle.getStateTopic().remove(s);
    }

    private void waitForDependersToExit() throws InterruptedException {

        List<EvergreenService> dependers = getHardDependers();
        Subscriber dependerExitWatcher = (WhatHappened what, Topic t) -> {
            synchronized (dependersExitedLock) {
                if (dependersExited(dependers)) {
                    dependersExitedLock.notifyAll();
                }
            }
        };
        // subscribing to depender state changes
        dependers.forEach(dependerEvergreenService -> dependerEvergreenService.addStateSubscriber(dependerExitWatcher));

        synchronized (dependersExitedLock) {
            while (!dependersExited(dependers)) {
                logger.atDebug("service-waiting-for-depender-to-finish").log();
                dependersExitedLock.wait();
            }
        }
        // removing state change watchers
        dependers.forEach(
                dependerEvergreenService -> dependerEvergreenService.removeStateSubscriber(dependerExitWatcher));
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

    protected boolean dependencyReady() {
        List<EvergreenService> ret =
                dependencies.entrySet().stream().filter(e -> !dependencyReady(e.getKey(), e.getValue().dependencyType))
                        .map(Map.Entry::getKey).collect(Collectors.toList());
        if (!ret.isEmpty()) {
            logger.atDebug("continue-waiting-for-dependencies").kv("waitingFor", ret).log();
        }
        return ret.isEmpty();
    }

    private boolean dependencyReady(EvergreenService v, DependencyType dependencyType) {
        State state = v.getState();
        // Soft dependency can be in any state, while hard dependency has to be in RUNNING, STOPPING or FINISHED.
        return dependencyType.equals(DependencyType.SOFT) || state.isHappy() && State.RUNNING.preceedsOrEqual(state);
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
        return getServiceName();
    }

    private String getServiceName() {
        return config == null ? getClass().getSimpleName() : config.getName();
    }

    public Topics getServiceConfig() {
        return config;
    }

    /**
     * Get the config topics for service local data-store during runtime. content under runtimeConfig will not be
     * affected by DeploymentService or DeploymentService roll-back.
     *
     * @return
     */
    public Topics getRuntimeConfig() {
        return config.lookupTopics(RUNTIME_STORE_NAMESPACE_TOPIC);
    }

    public Topics getPrivateConfig() {
        return privateConfig;
    }

    /**
     * Parse the list of dependencies into a list of service name and dependency type.
     *
     * @param dependencyList list of strings to be parsed
     * @return list of service name and dependency type
     * @throws InputValidationException if it fails to parse any entry
     */
    public static Iterable<Pair<String, DependencyType>> parseDependencies(Collection<String> dependencyList)
            throws InputValidationException {
        List<Pair<String, DependencyType>> ret = new ArrayList<>(dependencyList.size());
        for (String dependency : dependencyList) {
            ret.add(parseSingleDependency(dependency));
        }
        return ret;
    }

    protected Map<EvergreenService, DependencyType> getDependencyTypeMap(Collection<String> dependencyList)
            throws InputValidationException, ServiceLoadException {
        HashMap<EvergreenService, DependencyType> ret = new HashMap<>();
        for (Pair<String, DependencyType> dep : parseDependencies(dependencyList)) {
            ret.put(context.get(Kernel.class).locate(dep.getLeft()), dep.getRight());
        }
        return ret;
    }

    /**
     * Parse a string into a dependency specification.
     *
     * @param dependency string in the format of one service dependency
     * @return a pair of dependency name and type
     * @throws InputValidationException if the dependency string has invalid format
     */
    public static Pair<String, DependencyType> parseSingleDependency(String dependency)
            throws InputValidationException {
        String[] dependencyInfo = dependency.split(":");
        if (dependencyInfo.length == 0 || dependencyInfo.length > 2) {
            throw new InputValidationException("Bad dependency syntax");
        }
        String typeString = dependencyInfo.length > 1 ? dependencyInfo[1] : null;
        DependencyType type = null;
        if (typeString != null && !typeString.isEmpty()) {
            // do "friendly" match
            for (DependencyType s : DependencyType.values()) {
                if (typeString.regionMatches(true, 0, s.name(), 0, typeString.length())) {
                    type = s;
                    break;
                }
            }
            if (type == null) {
                throw new InputValidationException(typeString + " does not match any Service dependency type");
            }
        }

        return new Pair<>(dependencyInfo[0], type == null ? DependencyType.HARD : type);
    }

    private synchronized void setupDependencies(Collection<String> dependencyList)
            throws ServiceLoadException, InputValidationException {
        Map<EvergreenService, DependencyType> oldDependencies = new HashMap<>(getDependencies());
        Map<EvergreenService, DependencyType> keptDependencies = getDependencyTypeMap(dependencyList);

        Set<EvergreenService> removedDependencies = dependencies.entrySet().stream()
                .filter(e -> !keptDependencies.containsKey(e.getKey()) && !e.getValue().isDefaultDependency)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (!removedDependencies.isEmpty()) {
            logger.atInfo("removing-unused-dependencies").kv("removedDependencies", removedDependencies).log();

            removedDependencies.forEach(dependency -> {
                DependencyInfo dependencyInfo = dependencies.remove(dependency);
                dependency.removeStateSubscriber(dependencyInfo.stateTopicSubscriber);
            });
            context.get(Kernel.class).clearODcache();
        }

        AtomicBoolean hasNewService = new AtomicBoolean(false);
        keptDependencies.forEach((dependentEvergreenService, dependencyType) -> {
            try {
                if (!oldDependencies.containsKey(dependentEvergreenService)) {
                    hasNewService.set(true);
                }
                addOrUpdateDependency(dependentEvergreenService, dependencyType, false);
            } catch (InputValidationException e) {
                logger.atWarn("add-dependency").log("Unable to add dependency {}", dependentEvergreenService, e);
            }
        });

        if (hasNewService.get()) {
            requestRestart();
        } else if (!dependencyReady() && !getState().equals(State.FINISHED)) {
            // if dependency type changed, restart this service.
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
    public Map<EvergreenService, DependencyType> getDependencies() {
        return dependencies.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().dependencyType));
    }

    // TODO: Rewrite this builtin service detection, reconsider if it is needed at all, reconsider how it is implemented
    // If a service is a Builtin service, it is supposed to auto-start after kernel launches or deployment
    public boolean isBuiltin() {
        ImplementsService serviceAnnotation = getClass().getAnnotation(ImplementsService.class);
        return serviceAnnotation != null && serviceAnnotation.autostart();
    }

    public boolean shouldAutoStart() {
        return true;
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
        // dependency type. Default to be HARD.
        DependencyType dependencyType;
        // true if the dependency isn't explicitly declared in config
        boolean isDefaultDependency;
        Subscriber stateTopicSubscriber;
    }
}
