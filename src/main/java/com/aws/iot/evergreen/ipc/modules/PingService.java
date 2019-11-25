package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;

import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.handler.MessageDispatcher;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;


import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.common.Constants.PING_OP_CODE;
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
            messageDispatcher.registerOpCodeCallBack(PING_OP_CODE, this::ping);
        } catch (IPCException e) {
            log.log(Level.Error,"Error registering call back for opcode "+ PING_OP_CODE);
        }
    }

    public Message ping(Message request) {
        try {
            String req = new String(request.getPayload());
            if (req.equals("ping")) {
                log.log(Level.Note, "Ping received");
                return new Message(PING_OP_CODE, "pong".getBytes());
            }
        } catch (Exception e) {
            log.log(Level.Error, "Failed to respond to ping", e);
        }
        return null;
    }
}
