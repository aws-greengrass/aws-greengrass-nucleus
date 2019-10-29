package com.aws.iot.ipc;

import com.aws.iot.config.Topics;
import com.aws.iot.dependency.ImplementsService;
import com.aws.iot.gg2k.GGService;
import com.aws.iot.gg2k.client.common.FrameReader.Message;

import javax.inject.Inject;

import static com.aws.iot.gg2k.client.common.FrameReader.*;

@ImplementsService(name = "pingpong", autostart = true)
public class PingPongService extends GGService {

    @Inject
    Dispatcher dispatcher;

    public static final int REQUEST_STATE_RESPONSE = 60;

    public PingPongService(Topics c) {
        super(c);
    }

    @Override
    public void postInject(){
        dispatcher.addHandler(REQUEST_STATE_RESPONSE, (payload) -> test( payload));
    }

    public Message test(Message request) {
        try {
            String req = new String(request.getPayload());
            if (req.equals("ping") ) {
                return new Message(60, RequestType.REQUEST_RESPONSE, "pong".getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
