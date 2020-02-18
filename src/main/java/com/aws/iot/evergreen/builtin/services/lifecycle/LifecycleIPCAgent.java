package com.aws.iot.evergreen.builtin.services.lifecycle;

import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleListenRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleRequestTypes;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateChangeRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateTransitionEvent;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;

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

    private EvergreenService.GlobalStateChangeListener onServiceChange = (service, prev, active) -> {
        Map<ConnectionContext, BiConsumer<State, State>> callbacks = listeners.get(service.getName());
        if (callbacks != null) {
            callbacks.values().forEach(x -> x.accept(prev, active));
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
     * @param req     incoming request
     * @param context caller context
     * @return response for setting state
     */
    public GeneralResponse<Void, LifecycleResponseStatus> reportState(StateChangeRequest req,
                                                                      ConnectionContext context) {
        State s = State.valueOf(req.getState());
        Optional<EvergreenService> service =
                Optional.ofNullable(kernel.context.get(EvergreenService.class, context.getServiceName()));

        GeneralResponse<Void, LifecycleResponseStatus> resp = new GeneralResponse<>();
        if (service.isPresent()) {
            service.get().addDesiredState(s);
            resp.setError(LifecycleResponseStatus.Success);
        } else {
            resp.setError(LifecycleResponseStatus.InvalidRequest);
            resp.setErrorMessage("Service could not be found");
        }

        return resp;
    }

    /**
     * Set up a listener for state changes for a requested service. (Currently any service can listen to any other
     * service's lifecycle changes).
     *
     * @param listenRequest incoming listen request
     * @param context       caller context
     * @return response
     */
    public GeneralResponse<Void, LifecycleResponseStatus> listenToStateChanges(LifecycleListenRequest listenRequest,
                                                                               ConnectionContext context) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        listeners.compute(listenRequest.getServiceName(), (key, old) -> {
            if (old == null) {
                old = new ConcurrentHashMap<>();
            }
            context.onDisconnect(() -> listeners.values().forEach(map -> map.remove(context)));
            old.put(context, sendStateUpdateToListener(listenRequest, context));
            return old;
        });

        return GeneralResponse.<Void, LifecycleResponseStatus>builder().error(LifecycleResponseStatus.Success).build();
    }

    private BiConsumer<State, State> sendStateUpdateToListener(LifecycleListenRequest listenRequest,
                                                               ConnectionContext context) {
        return (oldState, newState) -> {
            executor.submit(() -> {
                // Synchronize on context so that we only try to send 1 update at a time to a given client
                synchronized (context) {
                    StateTransitionEvent trans =
                            StateTransitionEvent.builder().newState(newState.toString()).oldState(oldState.toString())
                                    .service(listenRequest.getServiceName()).build();

                    GeneralRequest<StateTransitionEvent, LifecycleRequestTypes> req =
                            GeneralRequest.<StateTransitionEvent, LifecycleRequestTypes>builder()
                                    .type(LifecycleRequestTypes.transition).request(trans).build();

                    try {
                        // TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                        context.serverPush(BuiltInServiceDestinationCode.LIFECYCLE.getValue(),
                                new FrameReader.Message(IPCUtil.encode(req))).get();
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
