package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientNotAuthorizedException;
import com.aws.iot.evergreen.ipc.services.common.AuthRequestTypes;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.SendAndReceiveIPCUtil;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.IOException;
import javax.inject.Inject;

@AllArgsConstructor
@NoArgsConstructor
public class AuthHandler implements InjectionActions {
    public static final String AUTH_TOKEN_LOOKUP_KEY = "_AUTH_TOKENS";

    @Inject
    private Configuration config;

    public static void registerAuthToken(EvergreenService s) {
        Topic uid = s.config.createLeafChild("_UID").setParentNeedsToKnow(false);
        String authToken = Utils.generateRandomString(16).toUpperCase();
        uid.setValue(authToken);
        s.config.parent.lookup(AUTH_TOKEN_LOOKUP_KEY, authToken).setValue(s.getName());
    }

    /**
     * @param request
     * @return
     * @throws IPCClientNotAuthorizedException
     */
    public RequestContext doAuth(FrameReader.Message request) throws IPCClientNotAuthorizedException {
        GeneralRequest<String, AuthRequestTypes> decodedRequest;
        try {
            decodedRequest = SendAndReceiveIPCUtil.decode(request, new TypeReference<GeneralRequest<String, AuthRequestTypes>>() {
            });
        } catch (IOException e) {
            throw new IPCClientNotAuthorizedException(e.getMessage());
        }

        String authToken = decodedRequest.getRequest();

        // Lookup the provided auth token to associate it with a service (or reject it)
        String serviceName = (String) config.lookup(AUTH_TOKEN_LOOKUP_KEY, authToken).getOnce();

        if (serviceName == null) {
            throw new IPCClientNotAuthorizedException("Auth token not found");
        }

        RequestContext context = new RequestContext();
        context.serviceName = serviceName;
        return context;
    }
}
