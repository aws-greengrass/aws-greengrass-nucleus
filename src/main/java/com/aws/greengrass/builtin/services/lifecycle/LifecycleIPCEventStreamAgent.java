package com.aws.greengrass.builtin.services.lifecycle;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import generated.software.amazon.awssdk.iot.greengrass.GeneratedAbstractDeferComponentUpdateOperationHandler;
import generated.software.amazon.awssdk.iot.greengrass.GeneratedAbstractSubscribeToComponentUpdatesOperationHandler;
import generated.software.amazon.awssdk.iot.greengrass.GeneratedAbstractUpdateStateOperationHandler;
import generated.software.amazon.awssdk.iot.greengrass.model.ComponentUpdatePolicyEvents;
import generated.software.amazon.awssdk.iot.greengrass.model.DeferComponentUpdateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.DeferComponentUpdateResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.InvalidArgumentError;
import generated.software.amazon.awssdk.iot.greengrass.model.PostComponentUpdateEvent;
import generated.software.amazon.awssdk.iot.greengrass.model.PreComponentUpdateEvent;
import generated.software.amazon.awssdk.iot.greengrass.model.ResourceNotFoundError;
import generated.software.amazon.awssdk.iot.greengrass.model.ServiceError;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToComponentUpdatesRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToComponentUpdatesResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateStateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateStateResponse;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;
import software.amazon.eventstream.iot.server.ServerStreamEventPublisher;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import javax.inject.Inject;

public class LifecycleIPCEventStreamAgent {

    @Getter (AccessLevel.PACKAGE)
    private final ConcurrentHashMap<String, Set<ServerStreamEventPublisher<ComponentUpdatePolicyEvents>>>
            componentUpdateListeners = new ConcurrentHashMap<>();

    // When a PreComponentUpdateEvent is pushed to components, a future is created for each component. When the
    // component responds with DeferComponentUpdateRequest the future is marked as complete. The caller of
    // sendPreComponentUpdateEvent will have reference to the set of futures.
    // deferUpdateFuturesMap maps the context of a component to the future created for the component.
    // This map is from service name to the Futures. Only one (latest) Future per service is maintained.
    @Getter (AccessLevel.PACKAGE)
    private final Map<String, CompletableFuture<DeferUpdateRequest>> deferUpdateFuturesMap =
            new ConcurrentHashMap<>();

    private static Logger log = LogManager.getLogger(LifecycleIPCEventStreamAgent.class);

    @Inject
    @Setter (AccessLevel.PACKAGE)
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
            log.atInfo().log("Handling update state request");
            State s = State.valueOf(request.getState().toString());
            String serviceN = request.getServiceName() == null ? serviceName : request.getServiceName();
            GreengrassService service;
            try {
                log.atInfo().log("Updating the state of a service");
                service = kernel.locate(serviceN);
                service.reportState(s);
                log.atInfo().log("Update the state of service {} to {}", serviceN, s.toString());
            } catch (ServiceLoadException e) {
                log.atWarn().kv("service name", request.getServiceName()).log("Service not present");
                ResourceNotFoundError rnf = new ResourceNotFoundError();
                rnf.setMessage("Service with given name not found");
                rnf.setResourceType("Service/Component");
                rnf.setResourceName(request.getServiceName());
                throw rnf;
            }

            return new UpdateStateResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamableJsonMessage streamRequestEvent) {
            // NA
        }
    }


    class SubscribeToComponentUpdateOperationHandler extends
            GeneratedAbstractSubscribeToComponentUpdatesOperationHandler {

        private final String serviceName;

        public SubscribeToComponentUpdateOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            log.atInfo("Stream closed for subscribeToComponentUpdate");
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
                log.atWarn().kv("service name", serviceName).setCause(e)
                        .log("Got subscribe to component update request from a "
                        + "service that is not found in Greengrass");
                ResourceNotFoundError rnf = new ResourceNotFoundError();
                rnf.setMessage("Service with given name not found currently in Greengrass");
                rnf.setResourceType("Service/Component");
                rnf.setResourceName(serviceName);
                throw rnf;
            }
            componentUpdateListeners.putIfAbsent(serviceName, new HashSet<>());
            componentUpdateListeners.get(serviceName).add(this);
            log.atInfo().log("{} subscribed to component update", serviceName);
            return new SubscribeToComponentUpdatesResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamableJsonMessage streamRequestEvent) {
            // NA
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
            // TODO: Input validation. https://sim.amazon.com/issues/P32540011

            if (!componentUpdateListeners.containsKey(serviceName)) {
                throw new InvalidArgumentError("Component is not subscribed to component update events");
            }

            CompletableFuture<DeferUpdateRequest> deferComponentUpdateRequestFuture =
                    deferUpdateFuturesMap.get(serviceName);
            if (deferComponentUpdateRequestFuture == null) {
                throw new ServiceError("Time limit to respond to PreComponentUpdateEvent exceeded");
            }
            deferComponentUpdateRequestFuture.complete(new DeferUpdateRequest(serviceName,
                    request.getMessage(), request.getRecheckAfterMs()));
            deferUpdateFuturesMap.remove(serviceName);
            return new DeferComponentUpdateResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamableJsonMessage streamRequestEvent) {
            // NA
        }
    }

    /**
     * Signal components about pending component updates.
     *
     * @param preComponentUpdateEvent event sent to subscribed components
     * @param deferUpdateFutures      futures tracking the response to preComponentUpdateEvent
     */
    public void sendPreComponentUpdateEvent(PreComponentUpdateEvent preComponentUpdateEvent,
                                            List<Future<DeferUpdateRequest>> deferUpdateFutures) {
        discardDeferComponentUpdateFutures();
        for (Map.Entry<String, Set<ServerStreamEventPublisher<ComponentUpdatePolicyEvents>>> entry
                : componentUpdateListeners.entrySet()) {
            String serviceName = entry.getKey();
            entry.getValue().forEach(subscribeHandler -> {
                log.atInfo().kv("serviceName", serviceName).log("Sending preComponentUpdate event");
                ComponentUpdatePolicyEvents componentUpdatePolicyEvents = new ComponentUpdatePolicyEvents();
                componentUpdatePolicyEvents.setPreUpdateEvent(preComponentUpdateEvent);
                subscribeHandler.sendStreamEvent(componentUpdatePolicyEvents);
                CompletableFuture<DeferUpdateRequest> deferUpdateFuture = new CompletableFuture<>();
                deferUpdateFutures.add(deferUpdateFuture);
                // If there are multiple pre component events sent to same service, we will store the latest future
                // As the update should be waiting for the latest one to complete.
                deferUpdateFuturesMap.put(serviceName, deferUpdateFuture);
            });
        }
    }

    /**
     * Signal component that updates are complete.
     *
     * @param postComponentUpdateEvent event sent to subscribed components
     */
    public void sendPostComponentUpdateEvent(PostComponentUpdateEvent postComponentUpdateEvent) {
        for (Map.Entry<String, Set<ServerStreamEventPublisher<ComponentUpdatePolicyEvents>>> entry
                : componentUpdateListeners.entrySet()) {
            String serviceName = entry.getKey();
            entry.getValue().forEach(subscribeHandler -> {
                ComponentUpdatePolicyEvents componentUpdatePolicyEvents = new ComponentUpdatePolicyEvents();
                log.atInfo().kv("serviceName", serviceName).log("Sending postComponentUpdate event");
                componentUpdatePolicyEvents.setPostUpdateEvent(postComponentUpdateEvent);
                subscribeHandler.sendStreamEvent(componentUpdatePolicyEvents);
            });
        }
    }

    /**
     * Discard the futures used to track components responding to PreComponentUpdateEvent. This is invoked when
     * the max time limit to respond to PreComponentUpdateEvent is reached.
     */
    public void discardDeferComponentUpdateFutures() {
        log.debug("Discarding {} DeferComponentUpdateRequest futures", deferUpdateFuturesMap.size());
        deferUpdateFuturesMap.clear();
    }
}
