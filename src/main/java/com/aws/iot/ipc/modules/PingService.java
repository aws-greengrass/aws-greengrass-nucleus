package com.aws.iot.ipc.modules;

import com.aws.iot.config.Topics;
import com.aws.iot.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.gg2k.GGService;
import com.aws.iot.ipc.handler.DefaultHandlerImpl;
import com.aws.iot.util.Log;

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
        msgHandler.registerOpCodeHandler(PING_OP_CODE, this::test);
    }

    public Message test(Message request) {
        try {
            String req = new String(request.getPayload());
            if (req.equals("ping")) {
                log().log(Log.Level.Note, "Ping received");
                return new Message(60, "pong".getBytes());
            }
        } catch (Exception e) {
            log().log(Log.Level.Error, "Ping failed", e);
        }
        return null;
    }
}
