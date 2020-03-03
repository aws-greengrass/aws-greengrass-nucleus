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
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import javax.inject.Inject;

/**
 * Class to handle business logic for all Lifecycle requests over IPC.
 */
public class LifecycleIPCAgent implements InjectionActions {
    // Map from service that is listened to --> Map of connection --> Function to call when service state changes
    private static final Map<String, Map<ConnectionContext, BiConsumer<State, State>>> listeners =
            new ConcurrentHashMap<>();

    @Inject
    private Kernel kernel;

    @Inject
    private ExecutorService executor;

    private Logger log = LogManager.getLogger(LifecycleIPCAgent.class);

    private EvergreenService.GlobalStateChangeListener onServiceChange = (service, prev) -> {
        Map<ConnectionContext, BiConsumer<State, State>> callbacks = listeners.get(service.getName());
        if (callbacks != null) {
            callbacks.values().forEach(x -> x.accept(prev, service.getState()));
        }
    };

    public LifecycleIPCAgent() {
    }

    @Override
    public void postInject() {
        kernel.context.addGlobalStateChangeListener(onServiceChange);
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
                Optional.ofNullable(kernel.context.get(EvergreenService.class, context.getServiceName()));

        log.info(service.get().getName() + " reported state :" + s.toString());
        LifecycleGenericResponse lifecycleGenericResponse = new LifecycleGenericResponse();
        if (service.isPresent()) {
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
        listeners.compute(lifecycleListenRequest.getServiceName(), (key, old) -> {
            if (old == null) {
                old = new ConcurrentHashMap<>();
            }
            context.onDisconnect(() -> listeners.values().forEach(map -> map.remove(context)));
            old.put(context, sendStateUpdateToListener(lifecycleListenRequest, context));
            return old;
        });

        return LifecycleGenericResponse.builder().status(LifecycleResponseStatus.Success).build();
    }

    private BiConsumer<State, State> sendStateUpdateToListener(LifecycleListenRequest listenRequest,
                                                               ConnectionContext context) {
        return (oldState, newState) -> {
            executor.submit(() -> {
                // Synchronize on context so that we only try to send 1 update at a time to a given client
                synchronized (context) {
                    StateTransitionEvent stateTransitionEvent =
                            StateTransitionEvent.builder().newState(newState.toString()).oldState(oldState.toString())
                                    .service(listenRequest.getServiceName()).build();

                    log.info("Pushing state change notification to  " + listenRequest.getServiceName()
                            + " from " + oldState.toString() + " to " + newState.toString());
                    try {
                        ApplicationMessage applicationMessage =
                                ApplicationMessage.builder().version(LifecycleImpl.API_VERSION)
                                        .opCode(LifecycleClientOpCodes.STATE_TRANSITION.ordinal())
                                        .payload(IPCUtil.encode(stateTransitionEvent)).build();
                        // TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                        context.serverPush(BuiltInServiceDestinationCode.LIFECYCLE.getValue(),
                                new FrameReader.Message(applicationMessage.toByteArray())).get();
                        // TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                    } catch (IOException | InterruptedException | ExecutionException e) {
                        // Log
                        e.printStackTrace();
                    }
                }
            });
        };
    }
}
