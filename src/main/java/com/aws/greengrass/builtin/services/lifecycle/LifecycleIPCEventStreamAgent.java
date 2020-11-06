/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.lifecycle;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Pair;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractDeferComponentUpdateOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToComponentUpdatesOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractUpdateStateOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateResponse;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.PostComponentUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.PreComponentUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.ResourceNotFoundError;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
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
import javax.inject.Inject;

import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_STREAM_MESSAGE_TIMEOUT_SECONDS;

public class LifecycleIPCEventStreamAgent {
    private static final String COMPONENT_NAME = "componentName";
    private static final Logger logger = LogManager.getLogger(LifecycleIPCEventStreamAgent.class);

    @Getter(AccessLevel.PACKAGE)
    private final ConcurrentHashMap<String, Set<StreamEventPublisher<ComponentUpdatePolicyEvents>>>
            componentUpdateListeners = new ConcurrentHashMap<>();

    // When a PreComponentUpdateEvent is pushed to components, a future is created for each component. When the
    // component responds with DeferComponentUpdateRequest the future is marked as complete. The caller of
    // sendPreComponentUpdateEvent will have reference to the set of futures.
    // deferUpdateFuturesMap maps the context of a component to the future created for the component.
    // This map is from service name and deployment id to the Futures. Only one (latest) Future per service is
    // maintained.
    //TODO: [P41211652]: Remove the DeferUpdateRequest when we remove the LifecycleIPCAgent.
    @Getter(AccessLevel.PACKAGE)
    private final Map<Pair<String, String>, CompletableFuture<DeferUpdateRequest>> deferUpdateFuturesMap =
            new ConcurrentHashMap<>();

    private static final Logger log = LogManager.getLogger(LifecycleIPCEventStreamAgent.class);

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private Kernel kernel;

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
            log.atInfo().log("Got update state request for service " + serviceName);
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
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

    }


    class SubscribeToComponentUpdateOperationHandler
            extends GeneratedAbstractSubscribeToComponentUpdatesOperationHandler {

        private final String serviceName;

        public SubscribeToComponentUpdateOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            log.atInfo().log("Stream closed for subscribeToComponentUpdate");
            componentUpdateListeners.get(serviceName).remove(this);
            if (componentUpdateListeners.get(serviceName).isEmpty()) {
                componentUpdateListeners.remove(serviceName);
            }
        }

        @Override
        @SuppressWarnings("PMD.PreserveStackTrace")
        public SubscribeToComponentUpdatesResponse handleRequest(SubscribeToComponentUpdatesRequest request) {
            try {
                kernel.locate(serviceName);
            } catch (ServiceLoadException e) {
                log.atWarn().kv(COMPONENT_NAME, serviceName).log("Got subscribe to component update request from a "
                        + "component that is not found in Greengrass");
                ResourceNotFoundError rnf = new ResourceNotFoundError();
                rnf.setMessage("Component with given name not found currently in Greengrass");
                rnf.setResourceType("Component");
                rnf.setResourceName(serviceName);
                throw rnf;
            }
            componentUpdateListeners.putIfAbsent(serviceName, new HashSet<>());
            componentUpdateListeners.get(serviceName).add(this);
            log.atInfo().log("{} subscribed to component update", serviceName);
            return SubscribeToComponentUpdatesResponse.VOID;
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

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
            // TODO: [P32540011]: All IPC service requests need input validation
            logger.atInfo().log("Entering defer request handler");
            if (!componentUpdateListeners.containsKey(serviceName)) {
                throw new InvalidArgumentsError("Component is not subscribed to component update events");
            }
            if (request.getDeploymentId() == null) {
                throw new InvalidArgumentsError("Cannot defer the update, the deployment ID provided was null");
            }

            CompletableFuture<DeferUpdateRequest> deferComponentUpdateRequestFuture =
                    deferUpdateFuturesMap.remove(new Pair<>(serviceName, request.getDeploymentId()));
            if (deferComponentUpdateRequestFuture == null) {
                throw new ServiceError("Time limit to respond to PreComponentUpdateEvent exceeded");
            } else {
                deferComponentUpdateRequestFuture.complete(
                        new DeferUpdateRequest(serviceName, request.getMessage(), request.getDeploymentId(),
                                request.getRecheckAfterMs()));
            }
            logger.atInfo().log("Exiting defer request handler");
            return new DeferComponentUpdateResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    /**
     * Signal components about pending component updates.
     *
     * @param preComponentUpdateEvent event sent to subscribed components
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public List<Future<DeferUpdateRequest>> sendPreComponentUpdateEvent(
            PreComponentUpdateEvent preComponentUpdateEvent) {
        List<Future<DeferUpdateRequest>> deferUpdateFutures = new ArrayList<>();
        discardDeferComponentUpdateFutures();
        for (Map.Entry<String, Set<StreamEventPublisher<ComponentUpdatePolicyEvents>>> entry : componentUpdateListeners
                .entrySet()) {
            String serviceName = entry.getKey();
            entry.getValue().forEach(subscribeHandler -> {
                log.atInfo().kv(COMPONENT_NAME, serviceName).log("Sending preComponentUpdate event");
                ComponentUpdatePolicyEvents componentUpdatePolicyEvents = new ComponentUpdatePolicyEvents();
                componentUpdatePolicyEvents.setPreUpdateEvent(preComponentUpdateEvent);

                CompletableFuture<DeferUpdateRequest> deferUpdateFuture = new CompletableFuture<>();
                // If there are multiple pre component events sent to same service, we will store the latest future
                // As the update should be waiting for the latest one to complete.
                Pair<String, String> serviceAndDeployment =
                        new Pair<>(serviceName, preComponentUpdateEvent.getDeploymentId());
                // Save to the map before sending the event so that it will be there if
                // they respond very quickly
                deferUpdateFuturesMap.put(serviceAndDeployment, deferUpdateFuture);

                try {
                    subscribeHandler.sendStreamEvent(componentUpdatePolicyEvents)
                            .get(DEFAULT_STREAM_MESSAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.atError().setCause(e).kv(COMPONENT_NAME, serviceName)
                            .log("Failed to send the pre component update on stream");
                    deferUpdateFuturesMap.remove(serviceAndDeployment);
                    return;
                }
                deferUpdateFutures.add(deferUpdateFuture);
            });
        }
        return deferUpdateFutures;
    }

    /**
     * Signal component that updates are complete.
     *
     * @param postComponentUpdateEvent event sent to subscribed components
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void sendPostComponentUpdateEvent(PostComponentUpdateEvent postComponentUpdateEvent) {
        for (Map.Entry<String, Set<StreamEventPublisher<ComponentUpdatePolicyEvents>>> entry : componentUpdateListeners
                .entrySet()) {
            String serviceName = entry.getKey();
            entry.getValue().forEach(subscribeHandler -> {
                ComponentUpdatePolicyEvents componentUpdatePolicyEvents = new ComponentUpdatePolicyEvents();
                log.atDebug().kv(COMPONENT_NAME, serviceName).log("Sending postComponentUpdate event");
                componentUpdatePolicyEvents.setPostUpdateEvent(postComponentUpdateEvent);

                try {
                    subscribeHandler.sendStreamEvent(componentUpdatePolicyEvents)
                            .get(DEFAULT_STREAM_MESSAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.atError().setCause(e).kv(COMPONENT_NAME, serviceName)
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
}
