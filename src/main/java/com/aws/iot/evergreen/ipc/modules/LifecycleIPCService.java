package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.builtin.services.lifecycle.LifecycleIPCAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.handler.MessageDispatcher;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.SendAndReceiveIPCUtil;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleListenRequest;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleRequestTypes;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.iot.evergreen.ipc.services.lifecycle.StateChangeRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import javax.inject.Inject;
import java.io.IOException;

import static com.aws.iot.evergreen.ipc.services.lifecycle.Lifecycle.LIFECYCLE_SERVICE_NAME;
import static com.aws.iot.evergreen.util.Log.Level;


//TODO: see if this needs to be a GGService
@ImplementsService(name = "lifecycleipc", autostart = true)
public class LifecycleIPCService extends EvergreenService {
    private ObjectMapper mapper = new CBORMapper();

    //TODO: figure out how to inject the interface than the impl
    @Inject
    private MessageDispatcher messageDispatcher;

    @Inject
    Log log;

    @Inject
    private LifecycleIPCAgent agent;

    public LifecycleIPCService(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        try {
            messageDispatcher.registerServiceCallback(LIFECYCLE_SERVICE_NAME, this::handleMessage);
            messageDispatcher.registerConnectionClosedCallback(agent::handleConnectionClosed);
        } catch (IPCException e) {
            log.log(Level.Error,"Error registering callback for service "+ LIFECYCLE_SERVICE_NAME);
        }
    }

    public Message handleMessage(Message request, RequestContext context) {
        try {
            GeneralRequest<Object, LifecycleRequestTypes> obj = SendAndReceiveIPCUtil.decode(request, new TypeReference<GeneralRequest<Object, LifecycleRequestTypes>>() {});

            GeneralResponse<?, LifecycleResponseStatus> genResp = new GeneralResponse<>();
            switch (obj.getType()) {
                case listen:
                    LifecycleListenRequest listenRequest = mapper.convertValue(obj.getRequest(), LifecycleListenRequest.class);
                    genResp = agent.listen(listenRequest, context);
                    break;
                case setState:
                    StateChangeRequest stateChangeRequest = mapper.convertValue(obj.getRequest(), StateChangeRequest.class);
                    genResp = agent.setState(stateChangeRequest, context);
                    break;
                default:
                    genResp.setError(LifecycleResponseStatus.Unknown);
                    genResp.setErrorMessage("Unknown request type " + obj.getType());
                    break;
            }
            return new Message(SendAndReceiveIPCUtil.encode(genResp));

        } catch (Throwable e) {
            log.log(Level.Error, "Failed to respond to handleMessage", e);

            GeneralResponse<Void, LifecycleResponseStatus> errorResponse =
                    GeneralResponse.<Void, LifecycleResponseStatus>builder()
                            .error(LifecycleResponseStatus.Unknown)
                            .errorMessage(e.getMessage())
                            .build();

            try {
                return new Message(SendAndReceiveIPCUtil.encode(errorResponse));
            } catch (IOException ex) {
                log.log(Level.Error, "Couldn't even send them the error back", e);
            }
        }
        return null;
    }
}
