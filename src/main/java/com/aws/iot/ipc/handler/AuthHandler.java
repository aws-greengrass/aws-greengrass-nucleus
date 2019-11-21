package com.aws.iot.ipc.handler;

import com.aws.iot.evergreen.ipc.common.Constants;
import com.aws.iot.ipc.common.Connection;

import static com.aws.iot.evergreen.ipc.common.FrameReader.*;

public class AuthHandler {

    public AuthResponse doAuth(MessageFrame request, Connection connection) {

        // First frame should be the auth request
        if (request.message.getOpCode() != Constants.AUTH_OP_CODE) {
            return new AuthResponse(null,false,"Invalid Auth request");
        }

        if(!connection.isLocal()){
            // Do Auth
        }
        //TODO: implement
        String clientId = new String(request.message.getPayload());
        if(clientId.isEmpty()){
            return new AuthResponse(null,false,"ClientId is empty");
        }
        return new AuthResponse(clientId,true,null);
    }

    class AuthResponse{
        public final String clientId;
        public final boolean isAuthorized;
        public final String errorMsg;

        public AuthResponse(String clientId, boolean isAuthorized, String errorMsg) {
            this.clientId = clientId;
            this.isAuthorized = isAuthorized;
            this.errorMsg = errorMsg;
        }
    }
}
