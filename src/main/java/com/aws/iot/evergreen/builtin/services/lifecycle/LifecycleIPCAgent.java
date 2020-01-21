package com.aws.iot.evergreen.builtin.services.lifecycle;

import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.handler.MessageDispatcher;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.SendAndReceiveIPCUtil;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleListenRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleRequestTypes;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateChangeRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateTransitionEvent;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

import static com.aws.iot.evergreen.ipc.services.lifecycle.Lifecycle.LIFECYCLE_SERVICE_NAME;

/**
 * Class to handle business logic for all Lifecycle requests over IPC.
 */
public class LifecycleIPCAgent implements InjectionActions {
    private final static Map<String, Map<String, BiConsumer<State, State>>> listeners = new ConcurrentHashMap<>();

    @Inject
    private Kernel kernel;

    @Inject
    private MessageDispatcher messageDispatcher;
    private EvergreenService.GlobalStateChangeListener onServiceChange = (service, prev) -> {
        Map<String, BiConsumer<State, State>> callbacks = listeners.get(service.getName());
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
     * Set the state of the service which the request is coming from.
     *
     * @param req
     * @param context
     * @return
     */
    public GeneralResponse<Void, LifecycleResponseStatus> setState(StateChangeRequest req, RequestContext context) {
        State s = State.valueOf(req.state);
        Optional<EvergreenService> service = kernel.orderedDependencies().stream()
                .filter(k -> k.getName().equals(context.serviceName))
                .findFirst();

        GeneralResponse<Void, LifecycleResponseStatus> resp = new GeneralResponse<>();
        if (service.isPresent()) {
            service.get().setState(s);
            resp.setError(LifecycleResponseStatus.Success);
        } else {
            resp.setError(LifecycleResponseStatus.Unknown);
            resp.setErrorMessage("Service could not be found");
        }

        return resp;
    }

    /**
     * Set up a listener for state changes for a requested service. (Currently any service can listen to any other
     * service's lifecyle changes).
     *
     * @param listenRequest
     * @param context
     * @return
     */
    public GeneralResponse<Void, LifecycleResponseStatus> listen(LifecycleListenRequest listenRequest, RequestContext context) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        listeners.compute(listenRequest.getServiceName(), (key, old) -> {
            if (old == null) {
                old = new ConcurrentHashMap<>();
            }

            old.put(context.clientId, (oldState, newState) -> {
                StateTransitionEvent trans = StateTransitionEvent.builder()
                        .newState(newState.toString())
                        .oldState(oldState.toString())
                        .build();

                GeneralRequest<StateTransitionEvent, LifecycleRequestTypes> req =
                        GeneralRequest.<StateTransitionEvent, LifecycleRequestTypes>builder()
                                .type(LifecycleRequestTypes.transition)
                                .request(trans).build();

                try {
                    // TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                    messageDispatcher.sendMessage(context.clientId,
                            new FrameReader.Message(SendAndReceiveIPCUtil.encode(req)),
                            LIFECYCLE_SERVICE_NAME).get();
                    // TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                } catch (IOException | InterruptedException | ExecutionException e) {
                    // Log
                    e.printStackTrace();
                }
            });

            return old;
        });

        return GeneralResponse.<Void, LifecycleResponseStatus>builder().error(LifecycleResponseStatus.Success).build();
    }

    /**
     * Remove state listeners for each clientId when the client disconnects.
     *
     * @param clientId
     */
    public void handleConnectionClosed(String clientId) {
        // Remove all listeners for the closed client so we don't waste time trying to notify it
        listeners.values().forEach(map -> map.remove(clientId));
    }
}
