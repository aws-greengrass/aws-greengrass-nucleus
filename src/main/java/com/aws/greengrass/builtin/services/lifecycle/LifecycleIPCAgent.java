/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.lifecycle;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.common.ServiceEventHelper;
import com.aws.greengrass.ipc.services.lifecycle.DeferComponentUpdateRequest;
import com.aws.greengrass.ipc.services.lifecycle.DeferComponentUpdateResponse;
import com.aws.greengrass.ipc.services.lifecycle.DeferComponentUpdateResponse.DeferComponentUpdateResponseBuilder;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleGenericResponse;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleImpl;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleServiceOpCodes;
import com.aws.greengrass.ipc.services.lifecycle.PostComponentUpdateEvent;
import com.aws.greengrass.ipc.services.lifecycle.PreComponentUpdateEvent;
import com.aws.greengrass.ipc.services.lifecycle.SubscribeToComponentUpdatesResponse;
import com.aws.greengrass.ipc.services.lifecycle.UpdateStateRequest;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Future;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode.LIFECYCLE;

/**
 * Class to handle business logic for all Lifecycle requests over IPC.
 */
public class LifecycleIPCAgent {

    private final Set<ConnectionContext> componentUpdateListeners = new CopyOnWriteArraySet<>();

    // When a PreComponentUpdateEvent is pushed to components, a future is created for each component. When the
    // component responds with DeferComponentUpdateRequest the future is marked as complete. The caller of
    // sendPreComponentUpdateEvent will have reference to the set of futures.
    // deferUpdateFuturesMap maps the context of a component to the future created for the component.
    private final Map<ConnectionContext, CompletableFuture<DeferUpdateRequest>> deferUpdateFuturesMap =
            new ConcurrentHashMap<>();

    @Inject
    private Kernel kernel;

    @Inject
    private ServiceEventHelper serviceEventHelper;

    private static final Logger log = LogManager.getLogger(LifecycleIPCAgent.class);

    /**
     * Report the state of the service which the request is coming from.
     *
     * @param updateStateRequest incoming request
     * @param context            caller context
     * @return response for setting state
     */
    public LifecycleGenericResponse updateState(UpdateStateRequest updateStateRequest, ConnectionContext context) {

        State s = State.valueOf(updateStateRequest.getState());
        Optional<GreengrassService> service =
                Optional.ofNullable(kernel.getContext().get(GreengrassService.class, context.getServiceName()));

        LifecycleGenericResponse lifecycleGenericResponse = new LifecycleGenericResponse();
        if (service.isPresent()) {
            log.info("{} reported state : {}", service.get().getName(), s);
            service.get().reportState(s);
            lifecycleGenericResponse.setStatus(LifecycleResponseStatus.Success);
        } else {
            lifecycleGenericResponse.setStatus(LifecycleResponseStatus.InvalidRequest);
            lifecycleGenericResponse.setErrorMessage("Service could not be found");
        }

        return lifecycleGenericResponse;
    }


    /**
     * handle component request to subscribe to component update events.
     *
     * @param context client context
     */
    public SubscribeToComponentUpdatesResponse subscribeToComponentUpdate(ConnectionContext context) {
        log.debug("{} subscribed to component update", context.getServiceName());
        componentUpdateListeners.add(context);
        context.onDisconnect(() -> componentUpdateListeners.remove(context));
        return SubscribeToComponentUpdatesResponse.builder().responseStatus(LifecycleResponseStatus.Success).build();
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
        componentUpdateListeners.forEach((context) -> {
            // GG_NEEDS_REVIEW: TODO: error handling if sendServiceEvent fails
            log.info("Sending preComponentUpdate event to {}", context.getServiceName());
            serviceEventHelper.sendServiceEvent(context, preComponentUpdateEvent, LIFECYCLE,
                    LifecycleServiceOpCodes.PRE_COMPONENT_UPDATE_EVENT.ordinal(), LifecycleImpl.API_VERSION);
            CompletableFuture<DeferUpdateRequest> deferUpdateFuture = new CompletableFuture<>();
            deferUpdateFutures.add(deferUpdateFuture);
            deferUpdateFuturesMap.put(context, deferUpdateFuture);
        });

    }

    /**
     * Signal component that updates are complete.
     *
     * @param postComponentUpdateEvent event sent to subscribed components
     */
    public void sendPostComponentUpdateEvent(PostComponentUpdateEvent postComponentUpdateEvent) {
        componentUpdateListeners.forEach((context) -> {
            log.info("Sending postComponentUpdate event to " + context.getServiceName());
            serviceEventHelper.sendServiceEvent(context, postComponentUpdateEvent, LIFECYCLE,
                    LifecycleServiceOpCodes.POST_COMPONENT_UPDATE_EVENT.ordinal(), LifecycleImpl.API_VERSION);
        });
    }

    /**
     * Discard the futures used to track components responding to PreComponentUpdateEvent. This is invoked when
     * the max time limit to respond to PreComponentUpdateEvent is reached.
     */
    public void discardDeferComponentUpdateFutures() {
        log.debug("Discarding {} DeferComponentUpdateRequest futures", deferUpdateFuturesMap.size());
        deferUpdateFuturesMap.clear();
    }

    /**
     * Accepts defer updates requests and sets the corresponding future with the information.
     *
     * @param request response for PreComponentUpdateEvent
     * @param context client context
     * @return response to the request
     */
    public DeferComponentUpdateResponse handleDeferComponentUpdateRequest(DeferComponentUpdateRequest request,
                                                                          ConnectionContext context) {
        // GG_NEEDS_REVIEW: TODO: Input validation. https://sim.amazon.com/issues/P32540011
        DeferComponentUpdateResponseBuilder responseBuilder = DeferComponentUpdateResponse.builder();
        if (!componentUpdateListeners.contains(context)) {
            return responseBuilder.responseStatus(LifecycleResponseStatus.InvalidRequest)
                    .errorMessage("Component is not subscribed to component update events").build();
        }

        CompletableFuture<DeferUpdateRequest> deferComponentUpdateRequestFuture =
                deferUpdateFuturesMap.get(context);
        if (deferComponentUpdateRequestFuture == null) {
            return responseBuilder.responseStatus(LifecycleResponseStatus.InvalidRequest)
                    .errorMessage("Time limit to respond to PreComponentUpdateEvent exceeded").build();
        }

        deferComponentUpdateRequestFuture.complete(new DeferUpdateRequest(context.getServiceName(),
                request.getMessage(), request.getRecheckTimeInMs()));
        deferUpdateFuturesMap.remove(context);
        return responseBuilder.responseStatus(LifecycleResponseStatus.Success).build();
    }
}
