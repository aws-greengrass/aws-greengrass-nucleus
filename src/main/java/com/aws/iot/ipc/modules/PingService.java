package com.aws.iot.ipc.modules;

import com.aws.iot.config.Topics;
import com.aws.iot.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.gg2k.GGService;
import com.aws.iot.ipc.exceptions.IPCGenericException;
import com.aws.iot.ipc.handler.DefaultHandlerImpl;
import com.aws.iot.util.Log;
import com.aws.iot.util.Log.Level;

import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.common.Constants.PING_OP_CODE;


//TODO: see if this needs to be a GGService
@ImplementsService(name = "ping", autostart = true)
public class PingService extends GGService {

    //TODO: figure out how to inject the interface than the impl
    @Inject
    private DefaultHandlerImpl msgHandler;


    public PingService(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        try {
            msgHandler.registerOpCodeCallBack(PING_OP_CODE, this::ping);
        } catch (IPCGenericException e) {
            log().log(Level.Error,"Error registering call back for opcode "+ PING_OP_CODE);
        }
    }

    public Message ping(Message request) {
        try {
            String req = new String(request.getPayload());
            if (req.equals("ping")) {
                log().log(Level.Note, "Ping received");
                return new Message(PING_OP_CODE, "pong".getBytes());
            }
        } catch (Exception e) {
            log().log(Level.Error, "Failed to respond to ping", e);
        }
        return null;
    }
}
