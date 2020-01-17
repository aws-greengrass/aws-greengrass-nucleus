package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientNotAuthorizedException;
import com.aws.iot.evergreen.kernel.Kernel;

import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.iot.evergreen.ipc.common.Constants.AUTH_SERVICE;
import static com.aws.iot.evergreen.ipc.common.FrameReader.MessageFrame;

public class AuthHandler implements InjectionActions {
    public static final String AUTH_TOKEN_TOPIC_NAME = "auth-tokens";

    @Inject
    private Configuration config;
    @Inject
    private Kernel kernel;

    /**
     *
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
        AtomicReference<String> serviceName = new AtomicReference<>();
        kernel.getRoot().deepForEachTopic(t -> {
            if(t.name.equals("_UID") && t.getOnce().equals(authToken)) {
                serviceName.set(t.parent.name);
            }
        });

        if (serviceName.get() == null) {
            throw new IPCClientNotAuthorizedException("Invalid Auth request");
        }
        RequestContext context = new RequestContext();
        context.clientId = clientId;
        context.serviceName = serviceName.get();
        return context;
    }
}
