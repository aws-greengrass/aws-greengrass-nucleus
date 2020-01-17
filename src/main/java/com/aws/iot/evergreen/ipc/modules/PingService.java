package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;

import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.handler.MessageDispatcher;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;


import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.common.Constants.PING_SERVICE;
import static com.aws.iot.evergreen.util.Log.*;


//TODO: see if this needs to be a GGService
@ImplementsService(name = "ping", autostart = true)
public class PingService extends EvergreenService {

    //TODO: figure out how to inject the interface than the impl
    @Inject
    private MessageDispatcher messageDispatcher;

    @Inject
    Log log;

    public PingService(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        try {
            messageDispatcher.registerServiceCallback(PING_SERVICE, this::ping);
        } catch (IPCException e) {
            log.log(Level.Error,"Error registering callback for service "+ PING_SERVICE);
        }
    }

    public Message ping(Message request, RequestContext context) {
        try {
            String req = new String(request.getPayload());
            if (req.equals("ping")) {
                log.log(Level.Note, "Ping received from service " + context.serviceName);
                return new Message("pong".getBytes());
            }
        } catch (Exception e) {
            log.log(Level.Error, "Failed to respond to ping", e);
        }
        return null;
    }
}
