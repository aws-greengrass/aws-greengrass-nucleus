package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientNotAuthorizedException;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.Utils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.common.Constants.AUTH_SERVICE;
import static com.aws.iot.evergreen.ipc.common.FrameReader.MessageFrame;

public class AuthHandler implements InjectionActions {
    public static final String AUTH_TOKEN_LOOKUP_KEY = "_AUTH_TOKENS";

    @Inject
    private Kernel kernel;

    public static void registerAuthToken(EvergreenService s) {
        Topic uid = s.config.createLeafChild("_UID").setParentNeedsToKnow(false);
        String authToken = Utils.generateRandomString(16).toUpperCase();
        uid.setValue(authToken);
        s.config.parent.lookup(AUTH_TOKEN_LOOKUP_KEY, authToken).setValue(s.getName());
    }

    /**
     * @param request
     * @return
     * @throws Exception
     */
    public RequestContext doAuth(MessageFrame request) throws IPCClientNotAuthorizedException {
        // First frame should be the auth request
        if (!request.destination.equals(AUTH_SERVICE)) {
            throw new IPCClientNotAuthorizedException("Invalid Auth request");
        }

        String authToken = new String(request.message.getPayload(), StandardCharsets.UTF_8);
        String clientId = UUID.randomUUID().toString();

        // Lookup the provided auth token to associate it with a service (or reject it)
        String serviceName = (String) kernel.getRoot().lookup(AUTH_TOKEN_LOOKUP_KEY, authToken).getOnce();

        if (serviceName == null) {
            throw new IPCClientNotAuthorizedException("Invalid Auth request");
        }
        RequestContext context = new RequestContext();
        context.clientId = clientId;
        context.serviceName = serviceName;
        return context;
    }
}
