package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.builtin.services.lifecycle.LifecycleIPCAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleListenRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleRequestTypes;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateChangeRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.services.lifecycle.Lifecycle.LIFECYCLE_SERVICE_NAME;
import static com.aws.iot.evergreen.util.Log.Level;


//TODO: see if this needs to be a GGService
@ImplementsService(name = "lifecycleipc", autostart = true)
public class LifecycleIPCService extends EvergreenService {
    private ObjectMapper mapper = new CBORMapper();

    @Inject
    private IPCRouter router;

    @Inject
    Log log;

    @Inject
    private LifecycleIPCAgent agent;

    public LifecycleIPCService(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        super.postInject();
        try {
            router.registerServiceCallback(LIFECYCLE_SERVICE_NAME, this::handleMessage);
        } catch (IPCException e) {
            log.log(Level.Error, "Error registering callback for service " + LIFECYCLE_SERVICE_NAME);
        }
    }

    /**
     * Handle all requests from the client.
     *
     * @param request the incoming request
     * @param context caller request context
     * @return future containing our response
     */
    public Future<Message> handleMessage(Message request, ConnectionContext context) {
        CompletableFuture<Message> fut = new CompletableFuture<>();

        try {
            GeneralRequest<Object, LifecycleRequestTypes> obj =
                    IPCUtil.decode(request, new TypeReference<GeneralRequest<Object, LifecycleRequestTypes>>() {
                    });

            GeneralResponse<?, LifecycleResponseStatus> genResp = new GeneralResponse<>();
            switch (obj.getType()) {
                case listen:
                    LifecycleListenRequest listenRequest =
                            mapper.convertValue(obj.getRequest(), LifecycleListenRequest.class);
                    genResp = agent.listenToStateChanges(listenRequest, context);
                    break;
                case setState:
                    StateChangeRequest stateChangeRequest =
                            mapper.convertValue(obj.getRequest(), StateChangeRequest.class);
                    genResp = agent.reportState(stateChangeRequest, context);
                    break;
                default:
                    genResp.setError(LifecycleResponseStatus.InvalidRequest);
                    genResp.setErrorMessage("Unknown request type " + obj.getType());
                    break;
            }
            fut.complete(new Message(IPCUtil.encode(genResp)));

        } catch (Throwable e) {
            log.log(Level.Error, "Failed to respond to handleMessage", e);

            GeneralResponse<Void, LifecycleResponseStatus> errorResponse =
                    GeneralResponse.<Void, LifecycleResponseStatus>builder()
                            .error(LifecycleResponseStatus.InternalError).errorMessage(e.getMessage()).build();

            try {
                fut.complete(new Message(IPCUtil.encode(errorResponse)));
            } catch (IOException ex) {
                log.log(Level.Error, "Couldn't even send them the error back", e);
            }
        }

        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }

        return fut;
    }
}
