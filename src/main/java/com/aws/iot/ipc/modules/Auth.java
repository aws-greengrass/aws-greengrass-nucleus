package com.aws.iot.ipc.modules;

import com.aws.iot.config.Topics;
import com.aws.iot.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.common.Constants;
import com.aws.iot.gg2k.GGService;

import com.aws.iot.ipc.handler.DefaultHandlerImpl;

import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.common.FrameReader.Message;

//TODO: see if this needs to be a GGService
@ImplementsService(name = "auth", autostart = true)
public class Auth extends GGService {

    //TODO: figure out how to inject the interface than the impl
    @Inject
    private DefaultHandlerImpl msgHandler;

    public Auth(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        msgHandler.registerOpCodeHandler(Constants.AUTH_OP_CODE, this::doAuth);
    }

    /**
     * Return message with Constants.ERROR_OP_CODE and error message as string in payload if not authenticated
     */
    public Message doAuth(Message request) {
        //TODO: implement auth using process uuid injected in env variable of external process
        return new Message(Constants.AUTH_OP_CODE, request.getPayload());
    }

}
