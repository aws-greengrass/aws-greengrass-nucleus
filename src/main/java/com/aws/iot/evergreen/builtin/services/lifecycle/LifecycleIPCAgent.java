package com.aws.iot.evergreen.builtin.services.lifecycle;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.IPCService;
import com.aws.iot.evergreen.ipc.Ipc;
import com.aws.iot.evergreen.ipc.impl.LifecycleImpl;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import io.grpc.stub.StreamObserver;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;

/**
 * Class to handle business logic for all Lifecycle requests over IPC.
 */
@ImplementsService(name = "lifecycleipc", autostart = true)
public class LifecycleIPCAgent extends EvergreenService implements InjectionActions {
    private final static Map<String, List<BiConsumer<State, State>>> listeners = new ConcurrentHashMap<>();

    @Inject
    private Kernel kernel;

    @Inject
    private ThreadPoolExecutor executor;

    private EvergreenService.GlobalStateChangeListener onServiceChange = (service, prev) -> {
        List<BiConsumer<State, State>> callbacks = listeners.get(service.getName());
        State currentState = service.getState();
        if (callbacks != null) {
            // Run all callbacks inside the executor so as not to block
            // the main subscription handler thread
            callbacks.forEach(x -> executor.submit(() -> x.accept(prev, currentState)));
        }
    };

    public LifecycleIPCAgent(Topics c) {
        super(c);
        IPCService.registerService(new LifecycleImpl(this));
    }

    @Override
    public void postInject() {
        kernel.context.addGlobalStateChangeListener(onServiceChange);
    }

    /**
     * Set the state of the service which the request is coming from.
     *
     * @param req
     * @param serviceName
     * @return
     */
    public GeneralResponse<Void, LifecycleResponseStatus> setState(Ipc.StateChangeRequest req, String serviceName) {
        State s = State.valueOf(req.getNewState());
        EvergreenService service = kernel.context.getv(EvergreenService.class, serviceName).get();

        GeneralResponse<Void, LifecycleResponseStatus> resp = new GeneralResponse<>();
        if (service != null) {
            service.setState(s);
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
     * @param serviceName
     * @param observer
     * @return
     */
    public GeneralResponse<Void, LifecycleResponseStatus> listen(Ipc.StateChangeListenRequest listenRequest,
                                                                 StreamObserver<Ipc.StateTransition> observer, String serviceName) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        listeners.compute(listenRequest.getService(), (key, old) -> {
            if (old == null) {
                old = new CopyOnWriteArrayList<>();
            }

            old.add((oldState, newState) -> {
                // Observer isn't threadsafe, so we need to sync on it
                synchronized (observer) {
                    observer.onNext(Ipc.StateTransition.newBuilder()
                            .setService(listenRequest.getService())
                            .setOldState(oldState.toString())
                            .setNewState(newState.toString())
                            .build());
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
