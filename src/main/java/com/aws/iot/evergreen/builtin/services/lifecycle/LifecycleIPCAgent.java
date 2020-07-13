package com.aws.iot.evergreen.builtin.services.lifecycle;

import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleClientOpCodes;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleGenericResponse;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleImpl;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleListenRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateChangeRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateTransitionEvent;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GlobalStateChangeListener;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.DefaultConcurrentHashMap;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import javax.inject.Inject;

/**
 * Class to handle business logic for all Lifecycle requests over IPC.
 */
public class LifecycleIPCAgent implements InjectionActions {
    // Map from service that is listened to --> Map of connection --> Function to call when service state changes
    private static final Map<String, Map<ConnectionContext, BiConsumer<State, State>>> listeners =
            new DefaultConcurrentHashMap<>(ConcurrentHashMap::new);
    private static final int TIMEOUT_SECONDS = 30;

    @Inject
    private Kernel kernel;

    @Inject
    private ExecutorService executor;

    private static final Logger log = LogManager.getLogger(LifecycleIPCAgent.class);

    private final GlobalStateChangeListener onServiceChange = (service, oldState, newState) -> {
        if (listeners.containsKey(service.getName())) {
            listeners.get(service.getName()).values().forEach(x -> x.accept(oldState, newState));
        }
    };

    @Override
    public void postInject() {
        kernel.getContext().addGlobalStateChangeListener(onServiceChange);
    }

    /**
     * Report the state of the service which the request is coming from.
     *
     * @param stateChangeRequest incoming request
     * @param context            caller context
     * @return response for setting state
     */
    public LifecycleGenericResponse reportState(StateChangeRequest stateChangeRequest, ConnectionContext context) {

        State s = State.valueOf(stateChangeRequest.getState());
        Optional<EvergreenService> service =
                Optional.ofNullable(kernel.getContext().get(EvergreenService.class, context.getServiceName()));

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
     * Set up a listener for state changes for a requested service. (Currently any service can listen to any other
     * service's lifecycle changes).
     *
     * @param lifecycleListenRequest incoming listen request
     * @param context                caller context
     * @return response
     */
    public LifecycleGenericResponse listenToStateChanges(LifecycleListenRequest lifecycleListenRequest,
                                                         ConnectionContext context) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        listeners.get(lifecycleListenRequest.getServiceName())
                .put(context, sendStateUpdateToListener(lifecycleListenRequest, context));
        context.onDisconnect(() -> listeners.values().forEach(map -> map.remove(context)));

        return LifecycleGenericResponse.builder().status(LifecycleResponseStatus.Success).build();
    }

    private BiConsumer<State, State> sendStateUpdateToListener(LifecycleListenRequest listenRequest,
                                                               ConnectionContext context) {
        return (oldState, newState) -> {
            StateTransitionEvent stateTransitionEvent =
                    StateTransitionEvent.builder().newState(newState.toString()).oldState(oldState.toString())
                            .service(listenRequest.getServiceName()).build();

            log.info("Pushing state change notification to {} from {} to {}", listenRequest.getServiceName(), oldState,
                    newState);
            try {
                ApplicationMessage applicationMessage = ApplicationMessage.builder().version(LifecycleImpl.API_VERSION)
                        .opCode(LifecycleClientOpCodes.STATE_TRANSITION.ordinal())
                        .payload(IPCUtil.encode(stateTransitionEvent)).build();
                // TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                Future<FrameReader.Message> fut = context.serverPush(BuiltInServiceDestinationCode.LIFECYCLE.getValue(),
                        new FrameReader.Message(applicationMessage.toByteArray()));

                // call the blocking "get" in a separate thread so we don't block the publish queue
                executor.execute(() -> {
                    try {
                        fut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        // TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        // Log
                        log.atError("error-sending-lifecycle-update").kv("context", context)
                                .log("Error sending lifecycle update to client", e);
                    }
                });

            } catch (IOException e) {
                // Log
                log.atError("error-sending-lifecycle-update").kv("context", context)
                        .log("Error sending lifecycle update to client", e);
            }
        };
    }
}
