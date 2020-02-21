package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.builtin.services.lifecycle.LifecycleIPCAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleGenericResponse;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleListenRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleServiceOpCodes;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateChangeRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

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
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.LIFECYCLE;
        super.postInject();
        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
        } catch (IPCException e) {
            log.log(Level.Error, "Error registering callback for service " + destination.name());
        }
    }

    /**
     * Handle all requests from the client.
     *
     * @param message the incoming request
     * @param context caller request context
     * @return future containing our response
     */
    public Future<Message> handleMessage(Message message, ConnectionContext context) {
        CompletableFuture<Message> fut = new CompletableFuture<>();

        ApplicationMessage applicationMessage = new ApplicationMessage(message.getPayload());
        try {
            //TODO: add version compatibility check
            LifecycleServiceOpCodes lifecycleServiceOpCodes =
                    LifecycleServiceOpCodes.values()[applicationMessage.getOpCode()];
            LifecycleGenericResponse lifecycleGenericResponse = new LifecycleGenericResponse();
            switch (lifecycleServiceOpCodes) {
                case REGISTER_LISTENER:
                    LifecycleListenRequest listenRequest =
                            mapper.readValue(applicationMessage.getPayload(), LifecycleListenRequest.class);
                    lifecycleGenericResponse = agent.listenToStateChanges(listenRequest, context);
                    break;
                case REPORT_STATE:
                    StateChangeRequest stateChangeRequest =
                            mapper.readValue(applicationMessage.getPayload(), StateChangeRequest.class);
                    lifecycleGenericResponse = agent.reportState(stateChangeRequest, context);
                    break;
                default:
                    lifecycleGenericResponse.setStatus(LifecycleResponseStatus.InvalidRequest);
                    lifecycleGenericResponse.setErrorMessage("Unknown request type "
                            + lifecycleServiceOpCodes.toString());
                    break;
            }

            ApplicationMessage responseMessage = ApplicationMessage.builder().version(applicationMessage.getVersion())
                    .payload(mapper.writeValueAsBytes(lifecycleGenericResponse)).build();
            fut.complete(new Message(responseMessage.toByteArray()));
        } catch (Throwable e) {
            log.log(Level.Error, "Failed to respond to handleMessage", e);
            try {
                LifecycleGenericResponse response = LifecycleGenericResponse.builder()
                        .status(LifecycleResponseStatus.InternalError).errorMessage(e.getMessage()).build();
                ApplicationMessage responseMessage = ApplicationMessage.builder()
                        .version(applicationMessage.getVersion())
                        .payload(mapper.writeValueAsBytes(response)).build();
                fut.complete(new Message(responseMessage.toByteArray()));
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
