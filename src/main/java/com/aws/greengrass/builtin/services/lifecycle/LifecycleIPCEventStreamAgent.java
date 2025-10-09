/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.lifecycle;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.unix.linux.LinuxPlatform;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractDeferComponentUpdateOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractPauseComponentOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractResumeComponentOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToComponentUpdatesOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractUpdateStateOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateResponse;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.PauseComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.PauseComponentResponse;
import software.amazon.awssdk.aws.greengrass.model.PostComponentUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.PreComponentUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.ResourceNotFoundError;
import software.amazon.awssdk.aws.greengrass.model.ResumeComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.ResumeComponentResponse;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.aws.greengrass.model.UpdateStateRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateStateResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.StreamEventPublisher;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_STREAM_MESSAGE_TIMEOUT_SECONDS;
import static com.aws.greengrass.ipc.common.ExceptionUtil.translateExceptions;
import static com.aws.greengrass.ipc.modules.LifecycleIPCService.LIFECYCLE_SERVICE_NAME;

public class LifecycleIPCEventStreamAgent {
    private static final String COMPONENT_NAME = "componentName";
    private static final Logger log = LogManager.getLogger(LifecycleIPCEventStreamAgent.class);

    // Listeners registered from generic external components (through IPC client)
    @Getter(AccessLevel.PACKAGE)
    private final ConcurrentHashMap<String, Set<StreamEventPublisher<ComponentUpdatePolicyEvents>>> componentUpdateListeners =
            new ConcurrentHashMap<>();

    // Listeners registered from plugins
    private final ConcurrentHashMap<String, Set<Consumer<ComponentUpdatePolicyEvents>>> componentUpdateListenersInternal =
            new ConcurrentHashMap<>();

    // When a PreComponentUpdateEvent is pushed to components, a future is created for each component. When the
    // component responds with DeferComponentUpdateRequest the future is marked as complete. The caller of
    // sendPreComponentUpdateEvent will have reference to the set of futures.
    // deferUpdateFuturesMap maps the context of a component to the future created for the component.
    // This map is from service name and deployment id to the Futures. Only one (latest) Future per service is
    // maintained.
    @Getter(AccessLevel.PACKAGE)
    private final Map<Pair<String, String>, CompletableFuture<DeferComponentUpdateRequest>> deferUpdateFuturesMap =
            new ConcurrentHashMap<>();

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private Kernel kernel;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private AuthorizationHandler authorizationHandler;

    public UpdateStateOperationHandler getUpdateStateOperationHandler(OperationContinuationHandlerContext context) {
        return new UpdateStateOperationHandler(context);
    }

    public SubscribeToComponentUpdateOperationHandler getSubscribeToComponentUpdateHandler(
            OperationContinuationHandlerContext context) {
        return new SubscribeToComponentUpdateOperationHandler(context);
    }

    public DeferComponentUpdateHandler getDeferComponentHandler(OperationContinuationHandlerContext context) {
        return new DeferComponentUpdateHandler(context);
    }

    public PauseComponentHandler getPauseComponentHandler(OperationContinuationHandlerContext context) {
        return new PauseComponentHandler(context);
    }

    public ResumeComponentHandler getResumeComponentHandler(OperationContinuationHandlerContext context) {
        return new ResumeComponentHandler(context);
    }

    class UpdateStateOperationHandler extends GeneratedAbstractUpdateStateOperationHandler {

        private final String serviceName;

        public UpdateStateOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        @SuppressWarnings("PMD.PreserveStackTrace")
        public UpdateStateResponse handleRequest(UpdateStateRequest request) {
            return translateExceptions(() -> {
                log.atDebug().log("Got update state request for component " + serviceName);
                GreengrassService service;
                try {
                    service = kernel.locate(serviceName);
                    State s = State.valueOf(request.getState().toString());
                    service.reportState(s);
                } catch (ServiceLoadException e) {
                    log.atWarn().log("Component {} not found", serviceName);
                    ResourceNotFoundError rnf = new ResourceNotFoundError();
                    rnf.setMessage("Component with given name not found");
                    rnf.setResourceType("Component");
                    rnf.setResourceName(serviceName);
                    throw rnf;
                }

                return new UpdateStateResponse();
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

    }

    class SubscribeToComponentUpdateOperationHandler
            extends
                GeneratedAbstractSubscribeToComponentUpdatesOperationHandler {

        private final String serviceName;

        public SubscribeToComponentUpdateOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            log.atDebug().log("Stream closed for subscribeToComponentUpdate");
            componentUpdateListeners.get(serviceName).remove(this);
            if (componentUpdateListeners.get(serviceName).isEmpty()) {
                componentUpdateListeners.remove(serviceName);
            }
        }

        @Override
        @SuppressWarnings("PMD.PreserveStackTrace")
        public SubscribeToComponentUpdatesResponse handleRequest(SubscribeToComponentUpdatesRequest request) {
            return translateExceptions(() -> {
                try {
                    subscribeToComponentUpdate(serviceName, () -> {
                        componentUpdateListeners.putIfAbsent(serviceName, new HashSet<>());
                        componentUpdateListeners.get(serviceName).add(this);
                    });
                } catch (ServiceLoadException e) {
                    log.atWarn()
                            .kv(COMPONENT_NAME, serviceName)
                            .log("Got subscribe to component update request from a component that is"
                                    + " not found in Greengrass");
                    ResourceNotFoundError rnf = new ResourceNotFoundError();
                    rnf.setMessage("Component with given name not found currently in Greengrass");
                    rnf.setResourceType("Component");
                    rnf.setResourceName(serviceName);
                    throw rnf;
                }
                return SubscribeToComponentUpdatesResponse.VOID;
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

    }

    /**
     * Subscribe to component update events internally, e.g. from a plugin.
     *
     * @param serviceName name of the service that is subscribing
     * @param updateEventCallback callback to invoke for sending update events
     * @throws ServiceLoadException when the requesting service cannot be located
     */
    public void subscribeToComponentUpdateInternal(String serviceName,
            Consumer<ComponentUpdatePolicyEvents> updateEventCallback) throws ServiceLoadException {
        subscribeToComponentUpdate(serviceName, () -> {
            componentUpdateListenersInternal.putIfAbsent(serviceName, new HashSet<>());
            componentUpdateListenersInternal.get(serviceName).add(updateEventCallback);
        });
    }

    private void subscribeToComponentUpdate(String serviceName, Runnable recordListener) throws ServiceLoadException {
        kernel.locate(serviceName);
        recordListener.run();

        log.atDebug().log("{} subscribed to component update", serviceName);
    }

    class DeferComponentUpdateHandler extends GeneratedAbstractDeferComponentUpdateOperationHandler {

        private final String serviceName;

        protected DeferComponentUpdateHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        public DeferComponentUpdateResponse handleRequest(DeferComponentUpdateRequest request) {
            return translateExceptions(() -> {
                // TODO: [P32540011]: All IPC service requests need input validation
                deferComponentUpdate(request, serviceName);
                return new DeferComponentUpdateResponse();
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    /**
     * Defer a component update.
     *
     * @param request DeferComponentUpdateRequest object
     * @param serviceName nam of the service deferring the update
     * @throws InvalidArgumentsError if service name or deployment id inputs are invalid
     */
    public void deferComponentUpdate(DeferComponentUpdateRequest request, String serviceName) {
        if (!componentUpdateListeners.containsKey(serviceName)
                && !componentUpdateListenersInternal.containsKey(serviceName)) {
            throw new InvalidArgumentsError("Component is not subscribed to component update events");
        }
        if (request.getDeploymentId() == null) {
            throw new InvalidArgumentsError("Cannot defer the update, the deployment ID provided was null");
        }

        CompletableFuture<DeferComponentUpdateRequest> deferComponentUpdateRequestFuture =
                deferUpdateFuturesMap.remove(new Pair<>(serviceName, request.getDeploymentId()));
        if (deferComponentUpdateRequestFuture == null) {
            throw new ServiceError("Time limit to respond to PreComponentUpdateEvent exceeded");
        } else {
            log.atDebug()
                    .log("Processing deployment deferral from {} for deployment {}", serviceName,
                            request.getDeploymentId());
            deferComponentUpdateRequestFuture.complete(request);
        }
    }

    /**
     * Signal components about pending component updates.
     *
     * @param preComponentUpdateEvent event sent to subscribed components
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public List<Future<DeferComponentUpdateRequest>> sendPreComponentUpdateEvent(
            PreComponentUpdateEvent preComponentUpdateEvent) {
        List<Future<DeferComponentUpdateRequest>> deferUpdateFutures = new ArrayList<>();
        discardDeferComponentUpdateFutures();

        // For callbacks registered by generic external components
        for (Map.Entry<String, Set<StreamEventPublisher<ComponentUpdatePolicyEvents>>> entry : componentUpdateListeners
                .entrySet()) {
            String serviceName = entry.getKey();
            entry.getValue().forEach(subscribeHandler -> {
                ComponentUpdatePolicyEvents events = makePreUpdateEvents(serviceName, preComponentUpdateEvent);

                CompletableFuture<DeferComponentUpdateRequest> deferUpdateFuture = new CompletableFuture<>();
                // If there are multiple pre component events sent to same service, we will store the latest future
                // As the update should be waiting for the latest one to complete.
                Pair<String, String> serviceAndDeployment =
                        new Pair<>(serviceName, preComponentUpdateEvent.getDeploymentId());
                // Save to the map before sending the event so that it will be there if
                // they respond very quickly
                deferUpdateFuturesMap.put(serviceAndDeployment, deferUpdateFuture);

                try {
                    subscribeHandler.sendStreamEvent(events)
                            .get(DEFAULT_STREAM_MESSAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.atError()
                            .setCause(e)
                            .kv(COMPONENT_NAME, serviceName)
                            .log("Failed to send the pre component update on stream");
                    deferUpdateFuturesMap.remove(serviceAndDeployment);
                    return;
                }
                deferUpdateFutures.add(deferUpdateFuture);
            });
        }

        // For internal callbacks registered by plugins
        for (Map.Entry<String, Set<Consumer<ComponentUpdatePolicyEvents>>> entry : componentUpdateListenersInternal
                .entrySet()) {
            String serviceName = entry.getKey();
            entry.getValue().forEach(subscribeHandler -> {
                ComponentUpdatePolicyEvents events = makePreUpdateEvents(serviceName, preComponentUpdateEvent);

                CompletableFuture<DeferComponentUpdateRequest> deferUpdateFuture = new CompletableFuture<>();
                // If there are multiple pre component events sent to same service, we will store the latest future
                // As the update should be waiting for the latest one to complete.
                Pair<String, String> serviceAndDeployment =
                        new Pair<>(serviceName, preComponentUpdateEvent.getDeploymentId());
                // Save to the map before sending the event so that it will be there if
                // they respond very quickly
                deferUpdateFuturesMap.put(serviceAndDeployment, deferUpdateFuture);

                try {
                    subscribeHandler.accept(events);
                } catch (Exception e) {
                    log.atError()
                            .setCause(e)
                            .kv(COMPONENT_NAME, serviceName)
                            .log("Failed to send the pre component update on stream");
                    deferUpdateFuturesMap.remove(serviceAndDeployment);
                    return;
                }
                deferUpdateFutures.add(deferUpdateFuture);
            });
        }

        return deferUpdateFutures;
    }

    private ComponentUpdatePolicyEvents makePreUpdateEvents(String serviceName,
            PreComponentUpdateEvent preComponentUpdateEvent) {
        log.atTrace().kv(COMPONENT_NAME, serviceName).log("Sending preComponentUpdate event");
        ComponentUpdatePolicyEvents componentUpdatePolicyEvents = new ComponentUpdatePolicyEvents();
        componentUpdatePolicyEvents.setPreUpdateEvent(preComponentUpdateEvent);
        return componentUpdatePolicyEvents;
    }

    private ComponentUpdatePolicyEvents makePostUpdateEvents(String serviceName,
            PostComponentUpdateEvent postComponentUpdateEvent) {
        ComponentUpdatePolicyEvents componentUpdatePolicyEvents = new ComponentUpdatePolicyEvents();
        log.atDebug().kv(COMPONENT_NAME, serviceName).log("Sending postComponentUpdate event");
        componentUpdatePolicyEvents.setPostUpdateEvent(postComponentUpdateEvent);
        return componentUpdatePolicyEvents;
    }

    /**
     * Signal component that updates are complete.
     *
     * @param postComponentUpdateEvent event sent to subscribed components
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void sendPostComponentUpdateEvent(PostComponentUpdateEvent postComponentUpdateEvent) {
        // For callbacks registered by generic external components
        for (Map.Entry<String, Set<StreamEventPublisher<ComponentUpdatePolicyEvents>>> entry : componentUpdateListeners
                .entrySet()) {
            String serviceName = entry.getKey();
            entry.getValue().forEach(subscribeHandler -> {
                ComponentUpdatePolicyEvents events = makePostUpdateEvents(serviceName, postComponentUpdateEvent);

                try {
                    subscribeHandler.sendStreamEvent(events)
                            .get(DEFAULT_STREAM_MESSAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.atError()
                            .setCause(e)
                            .kv(COMPONENT_NAME, serviceName)
                            .log("Failed to send the post component update on stream");
                }
            });
        }

        // For internal callbacks registered by plugins
        for (Map.Entry<String, Set<Consumer<ComponentUpdatePolicyEvents>>> entry : componentUpdateListenersInternal
                .entrySet()) {
            String serviceName = entry.getKey();
            entry.getValue().forEach(subscribeHandler -> {
                ComponentUpdatePolicyEvents events = makePostUpdateEvents(serviceName, postComponentUpdateEvent);

                try {
                    subscribeHandler.accept(events);
                } catch (Exception e) {
                    log.atError()
                            .setCause(e)
                            .kv(COMPONENT_NAME, serviceName)
                            .log("Failed to send the post component update on stream");
                }
            });
        }
    }

    /**
     * Discard the futures used to track components responding to PreComponentUpdateEvent. This is invoked when the max
     * time limit to respond to PreComponentUpdateEvent is reached.
     */
    public void discardDeferComponentUpdateFutures() {
        log.debug("Discarding {} DeferComponentUpdateRequest futures", deferUpdateFuturesMap.size());
        deferUpdateFuturesMap.clear();
    }

    class PauseComponentHandler extends GeneratedAbstractPauseComponentOperationHandler {

        private final String serviceName;

        protected PauseComponentHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public PauseComponentResponse handleRequest(PauseComponentRequest request) {
            return translateExceptions(() -> {
                if (!(Platform.getInstance() instanceof LinuxPlatform)) {
                    throw new ServiceError("Pause/resume component not supported on this platform.");
                }

                String componentName = request.getComponentName();
                if (Utils.isEmpty(componentName)) {
                    throw new InvalidArgumentsError("Component name is required.");
                }

                try {
                    doAuthorization(this.getOperationModelContext().getOperationName(), serviceName, componentName);
                } catch (AuthorizationException e) {
                    throw new UnauthorizedError(e.getMessage());
                }

                GreengrassService component;
                try {
                    component = kernel.locate(componentName);
                } catch (ServiceLoadException e) {
                    throw new ResourceNotFoundError();
                }

                GenericExternalService target;
                if (component instanceof GenericExternalService) {
                    target = (GenericExternalService) component;
                } else {
                    throw new InvalidArgumentsError("Only generic components can be paused.");
                }

                log.atDebug().log("Handling component pause for {}", componentName);
                if (State.RUNNING.equals(target.getState())) {
                    try {
                        target.pause();
                    } catch (ServiceException e) {
                        throw new ServiceError(String.format("Failed to pause component %s due to : %s", componentName,
                                e.getMessage()));
                    }
                } else {
                    throw new InvalidArgumentsError(String.format("Component %s is not running", componentName));
                }

                return new PauseComponentResponse();
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    class ResumeComponentHandler extends GeneratedAbstractResumeComponentOperationHandler {

        private final String serviceName;

        protected ResumeComponentHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public ResumeComponentResponse handleRequest(ResumeComponentRequest request) {
            return translateExceptions(() -> {
                if (!(Platform.getInstance() instanceof LinuxPlatform)) {
                    throw new ServiceError("Pause/resume component not supported on this platform.");
                }

                String componentName = request.getComponentName();
                if (Utils.isEmpty(componentName)) {
                    throw new InvalidArgumentsError("Component name is required.");
                }

                try {
                    doAuthorization(this.getOperationModelContext().getOperationName(), serviceName, componentName);
                } catch (AuthorizationException e) {
                    throw new UnauthorizedError(e.getMessage());
                }

                GreengrassService component;
                try {
                    component = kernel.locate(componentName);
                } catch (ServiceLoadException e) {
                    throw new ResourceNotFoundError();
                }

                GenericExternalService target;
                if (component instanceof GenericExternalService) {
                    target = (GenericExternalService) component;
                } else {
                    throw new InvalidArgumentsError("Only generic components can be resumed.");
                }

                log.atDebug().log("Handling component resume for {}", componentName);
                if (target.isPaused()) {
                    try {
                        target.resume();
                    } catch (ServiceException e) {
                        throw new ServiceError(String.format("Failed to resume component %s due to : %s", componentName,
                                e.getMessage()));
                    }
                } else {
                    throw new InvalidArgumentsError(String.format("Component %s is not paused", componentName));
                }

                return new ResumeComponentResponse();
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    private void doAuthorization(String opName, String serviceName, String targetComponent)
            throws AuthorizationException {
        authorizationHandler.isAuthorized(LIFECYCLE_SERVICE_NAME,
                Permission.builder().principal(serviceName).operation(opName).resource(targetComponent).build());
    }
}
