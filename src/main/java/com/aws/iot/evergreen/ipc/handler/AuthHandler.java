package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.ipc.common.Constants;
import com.aws.iot.evergreen.ipc.common.Connection;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientNotAuthorizedException;

import static com.aws.iot.evergreen.ipc.common.FrameReader.MessageFrame;

public class AuthHandler {
    /**
     *
     * @param request
     * @param connection
     * @return
     * @throws Exception
     */

    public String doAuth(MessageFrame request, Connection connection) throws IPCClientNotAuthorizedException {

        // First frame should be the auth request
        if (request.message.getOpCode() != Constants.AUTH_OP_CODE) {
            throw new IPCClientNotAuthorizedException("Invalid Auth request");
        }

        if(!connection.isLocal()){
            //TODO: Do Auth
        }
        //TODO: do this in a backward compatible way
        String clientId = new String(request.message.getPayload());
        if(clientId.isEmpty()){
            throw new IPCClientNotAuthorizedException("ClientId is empty");
        }
        return clientId;
    }

}
