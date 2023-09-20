/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.ComponentStatusCode;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.status.model.ComponentStatusDetails;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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

import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.NO_OP;
import static com.aws.greengrass.util.Utils.getUltimateCause;

@SuppressFBWarnings("JLM_JSR166_UTILCONCURRENT_MONITORENTER")
public class GreengrassService implements InjectionActions {
    public static final String SERVICES_NAMESPACE_TOPIC = "services";
    public static final String RUNTIME_STORE_NAMESPACE_TOPIC = "runtime";
    public static final String PRIVATE_STORE_NAMESPACE_TOPIC = "_private";
    public static final String SERVICE_LIFECYCLE_NAMESPACE_TOPIC = "lifecycle";
    public static final String SERVICE_DEPENDENCIES_NAMESPACE_TOPIC = "dependencies";
    public static final String ACCESS_CONTROL_NAMESPACE_TOPIC = "accessControl";
    public static final String SERVICE_NAME_KEY = "serviceName";
    public static final String SETENV_CONFIG_NAMESPACE = "setenv";
    public static final String RUN_WITH_NAMESPACE_TOPIC = "runWith";
    public static final String SYSTEM_RESOURCE_LIMITS_TOPICS = "systemResourceLimits";
    public static final String POSIX_USER_KEY = "posixUser";
    public static final String WINDOWS_USER_KEY = "windowsUser";
    public static final String CURRENT_STATE_METRIC_NAME = "currentState";
    public static final String KV_REASON = "reason";
    public static final String KV_STATUS_CODE = "statusCode";
    public static final String KV_EXIT_CODE = "exitCode";
    public static final String EVENT_SERVICE_ERRORED = "service-errored";

    @Getter
    protected final Topics config;
    private final Topics privateConfig;

    // TODO: [P41215222] make the field private
    @Getter
    public Context context;

    private final Lifecycle lifecycle;
    private final Object dependersExitedLock = new Object();
    private Throwable error;
    private final Periodicity periodicityInformation;
    private final Object dependencyReadyLock = new Object();

    // dependencies that are explicitly declared by customer in config store.
    private final Topic externalDependenciesTopic;
    private Subscriber externalDependenciesTopicWatcher;
    // Services that this service depends on.
    // Includes both explicit declared dependencies and implicit ones added through 'autoStart' and @Inject annotation.
    protected final ConcurrentHashMap<GreengrassService, DependencyInfo> dependencies = new ConcurrentHashMap<>();
    // Service logger instance
    protected final Logger logger;

    /**
     * Constructor.
     *
     * @param topics root Configuration topic for this service
     */
    public GreengrassService(Topics topics) {
        this(topics, topics.lookupTopics(PRIVATE_STORE_NAMESPACE_TOPIC));
    }

    /**
     * Constructor.
     *
     * @param topics        root Configuration topic for this service
     * @param privateConfig root configuration topic for the service's private config which must not be shared
     */
    public GreengrassService(Topics topics, Topics privateConfig) {
        this.config = topics;
        this.privateConfig = privateConfig;
        this.context = topics.getContext();

        // TODO: [P41215193]: Validate syntax for lifecycle keywords and fail early
        // skipif will require validation for onpath/exists etc. keywords

        this.logger = LogManager.getLogger(this.getClass()).createChild();
        logger.dfltKv(SERVICE_NAME_KEY, getServiceName());
        logger.dfltKv(CURRENT_STATE_METRIC_NAME, (Supplier<State>) this::getState);

        this.externalDependenciesTopic =
                topics.createLeafChild(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC).dflt(new ArrayList<String>());
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

    public boolean didStartupError() {
        return lifecycle.getStoppingFromStartupError().get();
    }

    public ComponentStatusDetails getStatusDetails() {
        return lifecycle.getStatusDetails();
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
     * Public API for service to report state. Allowed states are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's actual state
     */
    public void reportState(State newState) {
        lifecycle.reportState(newState);
    }

    /**
     * Public API for service to report state. Allowed states are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's actual state
     * @param statusCode status code associated with the reported state
     */
    public void reportState(State newState, ComponentStatusCode statusCode) {
        lifecycle.reportState(newState, statusCode);
    }

    /**
     * Public API for service to report state. Allowed states are RUNNING, FINISHED, ERRORED.
     *
     * @param newState reported state from the service which should eventually be set as the service's actual state
     * @param statusCode status code associated with the reported state
     * @param exitCode exit code associated with the reported state
     */
    public void reportState(State newState, ComponentStatusCode statusCode, int exitCode) {
        lifecycle.reportState(newState, statusCode, exitCode);
    }

    private void initDependenciesTopic() {
        synchronized (dependencies) {
            externalDependenciesTopicWatcher = (what, node) -> {
                if (!WhatHappened.changed.equals(what)) {
                    return;
                }
                Collection<String> depList = (Collection<String>) node.getOnce();
                logger.atDebug().log("Setting up dependencies again {}", String.join(",", depList));
                try {
                    setupDependencies(depList);
                } catch (ServiceLoadException | InputValidationException e) {
                    logger.atError().log("Error while setting up dependencies from subscription", e);
                }
            };
            externalDependenciesTopic.subscribe(externalDependenciesTopicWatcher);

            try {
                setupDependencies((Collection<String>) externalDependenciesTopic.getOnce());
            } catch (ServiceLoadException | InputValidationException e) {
                serviceErrored(e);
            }
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
     * Restart Service. Will not restart if the service has not been started once and there's no desired state.
     *
     * @return true if the request will happen, false otherwise.
     */
    public final boolean requestRestart() {
        return lifecycle.requestRestart();
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
        logger.atError(EVENT_SERVICE_ERRORED, e).log();
        reportState(State.ERRORED);
    }

    /**
     * Report that the service has hit an error.
     *
     * @param reason the reason to be logged
     */
    public void serviceErrored(String reason) {
        logger.atError(EVENT_SERVICE_ERRORED).kv(KV_REASON, reason).log();
        reportState(State.ERRORED);
    }

    /**
     * Report that the service has hit an error.
     *
     * @param statusCode the status code corresponding to the error
     */
    public void serviceErrored(ComponentStatusCode statusCode) {
        logger.atError(EVENT_SERVICE_ERRORED)
                .kv(KV_STATUS_CODE, statusCode.name())
                .log();
        reportState(State.ERRORED, statusCode);
    }

    /**
     * Report that the service has hit an error.
     *
     * @param statusCode the status code corresponding to the error
     * @param reason the reason to be logged
     */
    public void serviceErrored(ComponentStatusCode statusCode, String reason) {
        logger.atError(EVENT_SERVICE_ERRORED)
                .kv(KV_STATUS_CODE, statusCode.name())
                .kv(KV_REASON, reason)
                .log();
        reportState(State.ERRORED, statusCode);
    }

    /**
     * Report that the service has hit an error.
     *
     * @param statusCode the status code corresponding to the error
     * @param exitCode the exit code of the service process
     */
    public void serviceErrored(ComponentStatusCode statusCode, int exitCode) {
        logger.atError(EVENT_SERVICE_ERRORED)
                .kv(KV_STATUS_CODE, statusCode.name())
                .kv(KV_EXIT_CODE, exitCode)
                .log();
        reportState(State.ERRORED, statusCode, exitCode);
    }

    /**
     * Report that the service has hit an error.
     *
     * @param statusCode the status code corresponding to the error
     * @param exitCode the exit code of the service process
     * @param reason the reason to be logged
     */
    public void serviceErrored(ComponentStatusCode statusCode, int exitCode, String reason) {
        logger.atError(EVENT_SERVICE_ERRORED)
                .kv(KV_STATUS_CODE, statusCode.name())
                .kv(KV_EXIT_CODE, exitCode)
                .kv(KV_REASON, reason)
                .log();
        reportState(State.ERRORED, statusCode, exitCode);
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
        return NO_OP;
    }

    /**
     * Check if the proposed Nucleus config needs Nucleus to be restarted. Deployment workflow will call this to decide
     * if nucleus restart is needed. Default is false, Greengrass services should override this method to check if
     * specific nucleus config keys have changed, validate them and return if the change needs a nucleus restart.
     *
     * @param newNucleusConfig new nucleus component config for the update
     * @return true if the proposed nucleus config should cause nucleus restart
     * @throws ComponentConfigurationValidationException if the changed value for the nucleus configuration is invalid
     */
    public boolean restartNucleusOnNucleusConfigChange(Map<String, Object> newNucleusConfig)
            throws ComponentConfigurationValidationException {
        return false;
    }

    /**
     * Check if bootstrap step needs to run during service update. Called during deployments to determine deployment
     * workflow.
     *
     * @param newServiceConfig new service config for the update
     * @return true if bootstrap step needs to run, false otherwise
     */
    public boolean isBootstrapRequired(Map<String, Object> newServiceConfig) {
        return false;
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
     * Moves the service to finished state and shuts down lifecycle thread.
     *
     * @return future completes when the lifecycle thread shuts down.
     */
    public CompletableFuture<Void> close() {
        return close(true);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    protected CompletableFuture<Void> close(boolean waitForDependers) {
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();

        context.get(Executor.class).execute(() -> {
            logger.atInfo("service-close").log("Service is now closing");
            // set close to true so that service will be moving to terminated states
            // and no more start/restart/reinstall is allowed
            lifecycle.setClosed(true);
            try {
                Periodicity t = periodicityInformation;
                if (t != null) {
                    t.shutdown();
                }
                if (waitForDependers) {
                    try {
                        waitForDependersToExit();
                    } catch (InterruptedException e) {
                        logger.error("Interrupted waiting for dependers to exit");
                    }
                }
                // removing listeners on dependencies after the dependers have exited
                dependencies.forEach((service, dependencyInfo) ->
                        getContext().removeGlobalStateChangeListener(dependencyInfo.stateListener));
                externalDependenciesTopic.remove(externalDependenciesTopicWatcher);
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
     * @param dependencyService the service to add as a dependency.
     * @param dependencyType    type of the dependency.
     * @param isDefault         True if the dependency is added without explicit declaration in 'dependencies' Topic.
     * @throws InputValidationException if the provided arguments are invalid.
     */
    public void addOrUpdateDependency(GreengrassService dependencyService, DependencyType dependencyType,
                                      boolean isDefault)
            throws InputValidationException {
        if (dependencyService == null || dependencyType == null) {
            throw new InputValidationException("One or more parameters was null");
        }

        synchronized (dependencies) {
            dependencies.compute(dependencyService, (dependentService, dependencyInfo) -> {
                // If the dependency already exists, we should first remove the subscriber before creating the
                // new subscriber with updated input.
                if (dependencyInfo != null) {
                    getContext().removeGlobalStateChangeListener(dependencyInfo.stateListener);
                }
                GlobalStateChangeListener listener = createDependencyListener(dependencyService, dependencyType);
                getContext().addGlobalStateChangeListener(listener);
                context.get(Kernel.class).clearODcache();
                return new DependencyInfo(dependencyType, isDefault, listener);
            });
            // Clear cache after updating dependency list
            context.get(Kernel.class).clearODcache();
        }
    }

    private GlobalStateChangeListener createDependencyListener(GreengrassService dependencyService,
                                                               DependencyType dependencyType) {
        return (service, oldState, newState) -> {
            if (service.equals(dependencyService) && (State.STARTING.equals(getState()) || State.RUNNING.equals(
                    getState())) && !dependencyReady(dependencyService, dependencyType)) {
                requestRestart();
                logger.atInfo("service-restart").log("Restarting service because dependency {} was in a bad state",
                        dependencyService.getName());
            }
            synchronized (dependencyReadyLock) {
                if (dependencyReady()) {
                    dependencyReadyLock.notifyAll();
                }
            }
        };
    }

    private List<GreengrassService> getHardDependers() {
        List<GreengrassService> dependers = new ArrayList<>();
        Kernel kernel = context.get(Kernel.class);
        for (GreengrassService greengrassService : kernel.orderedDependencies()) {
            for (Map.Entry<GreengrassService, DependencyInfo> entry : greengrassService.dependencies.entrySet()) {
                if (entry.getKey().equals(this) && DependencyType.HARD.equals(entry.getValue().dependencyType)) {
                    dependers.add(greengrassService);
                }
            }
        }
        return dependers;
    }

    private void waitForDependersToExit() throws InterruptedException {

        List<GreengrassService> dependers = getHardDependers();
        List<GlobalStateChangeListener> watchers = new ArrayList<>();

        // subscribing to depender state changes
        dependers.forEach(dependerGreengrassService -> {
            GlobalStateChangeListener dependerExitWatcher = (service, oldState, newState) -> {
                if (service.equals(dependerGreengrassService)) {
                    synchronized (dependersExitedLock) {
                        if (dependersExited(dependers)) {
                            dependersExitedLock.notifyAll();
                        }
                    }
                }
            };
            getContext().addGlobalStateChangeListener(dependerExitWatcher);
            watchers.add(dependerExitWatcher);
        });

        synchronized (dependersExitedLock) {
            while (!dependersExited(dependers)) {
                logger.atDebug("service-waiting-for-dependent-to-finish").log();
                dependersExitedLock.wait();
            }
        }
        // removing state change watchers
        watchers.forEach(w -> getContext().removeGlobalStateChangeListener(w));
    }

    private boolean dependersExited(List<GreengrassService> dependers) {
        Optional<GreengrassService> dependerService =
                dependers.stream().filter(d -> !d.getState().isClosable()).findAny();
        if (dependerService.isPresent()) {
            logger.atDebug("continue-waiting-for-dependents").kv("waitingFor", dependerService.get().getName()).log();
            return false;
        }
        return true;
    }

    protected boolean dependencyReady() {
        List<GreengrassService> ret =
                dependencies.entrySet().stream().filter(e -> !dependencyReady(e.getKey(), e.getValue().dependencyType))
                        .map(Map.Entry::getKey).collect(Collectors.toList());
        if (!ret.isEmpty()) {
            logger.atDebug("continue-waiting-for-dependencies").kv("waitingFor", ret).log();
        }
        return ret.isEmpty();
    }

    private boolean dependencyReady(GreengrassService v, DependencyType dependencyType) {
        // Soft dependency can be in any state, while hard dependency has to be in RUNNING, STOPPING or FINISHED.
        return dependencyType.equals(DependencyType.SOFT) || dependencyFinishedStarting(v);
    }

    private boolean dependencyFinishedStarting(GreengrassService v) {
        State state = v.getState();
        // if the component is stopping, it's possible that is previous state sequence is
        // 1) starting -> errored -> stopping;
        // 2) starting -> running -> stopping;
        // 3) starting -> stopping;
        // 4) running -> errored -> stopping.
        // to differentiate case 1) and make sure we don't mark dependency as ready when its startup actually errored,
        // check if its errored count is non-zero
        return State.RUNNING.equals(state) || State.FINISHED.equals(state)
                || State.STOPPING.equals(state) && !v.didStartupError();
    }

    void waitForDependencyReady() throws InterruptedException {
        synchronized (dependencyReadyLock) {
            while (!dependencyReady()) {
                logger.atDebug("service-waiting-for-dependency").log();
                dependencyReadyLock.wait();
            }
        }
    }

    public void forAllDependencies(Consumer<? super GreengrassService> f) {
        dependencies.keySet().forEach(f);
    }

    public String getName() {
        return getServiceName();
    }

    public String getServiceName() {
        return config == null ? getClass().getSimpleName() : config.getName();
    }

    public Topics getServiceConfig() {
        return config;
    }

    public String getServiceType() {
        return Coerce.toString(config.findLeafChild(Kernel.SERVICE_TYPE_TOPIC_KEY));
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

    protected Map<GreengrassService, DependencyType> getDependencyTypeMap(Collection<String> dependencyList)
            throws InputValidationException, ServiceLoadException {
        HashMap<GreengrassService, DependencyType> ret = new HashMap<>();
        for (Pair<String, DependencyType> dep : parseDependencies(dependencyList)) {
            ret.put(context.get(Kernel.class).locateIgnoreError(dep.getLeft()), dep.getRight());
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

    private void setupDependencies(Collection<String> dependencyList)
            throws ServiceLoadException, InputValidationException {
        synchronized (dependencies) {
            Map<GreengrassService, DependencyType> oldDependencies = new HashMap<>(getDependencies());
            Map<GreengrassService, DependencyType> keptDependencies = getDependencyTypeMap(dependencyList);

            Set<GreengrassService> removedDependencies = dependencies.entrySet().stream()
                    .filter(e -> !keptDependencies.containsKey(e.getKey()) && !e.getValue().isDefaultDependency)
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            if (!removedDependencies.isEmpty()) {
                logger.atDebug("removing-unused-dependencies").kv("removedDependencies", removedDependencies).log();

                removedDependencies.forEach(dependency -> {
                    DependencyInfo dependencyInfo = dependencies.remove(dependency);
                    getContext().removeGlobalStateChangeListener(dependencyInfo.stateListener);
                });
                context.get(Kernel.class).clearODcache();
            }

            AtomicBoolean hasNewService = new AtomicBoolean(false);
            keptDependencies.forEach((dependentService, dependencyType) -> {
                try {
                    if (!oldDependencies.containsKey(dependentService)) {
                        hasNewService.set(true);
                    }
                    addOrUpdateDependency(dependentService, dependencyType, false);
                } catch (InputValidationException e) {
                    logger.atWarn("add-dependency").log("Unable to add dependency {}", dependentService, e);
                }
            });

            if (hasNewService.get()) {
                requestRestart();
            } else if (!dependencyReady() && !getState().equals(State.FINISHED)) {
                // if dependency type changed, restart this service.
                requestRestart();
            }
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

    protected void putDependenciesIntoSet(Set<GreengrassService> deps) {
        deps.add(this);
        dependencies.keySet().forEach(d -> {
            if (!deps.contains(d)) {
                d.putDependenciesIntoSet(deps);
            }
        });
    }

    // GG_NEEDS_REVIEW: TODO: return the entire dependency info
    public Map<GreengrassService, DependencyType> getDependencies() {
        return dependencies.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().dependencyType));
    }

    public boolean isBuiltin() {
        ImplementsService serviceAnnotation = getClass().getAnnotation(ImplementsService.class);
        return serviceAnnotation != null;
    }

    /**
     * Determines if the service should automatically start after a deployment.
     *
     * @return true if the service should be started after deployment
     */
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
        GlobalStateChangeListener stateListener;
    }
}
