package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientNotAuthorizedException;
import com.aws.iot.evergreen.ipc.services.common.AuthRequestTypes;
import com.aws.iot.evergreen.ipc.services.common.GeneralRequest;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.kernel.EvergreenService;
import org.junit.jupiter.api.Test;

import static com.aws.iot.evergreen.ipc.handler.AuthHandler.AUTH_TOKEN_LOOKUP_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthHandlerTest {
    private static final String SERVICE_NAME = "ServiceName";

    @Test
    public void GIVEN_service_WHEN_register_auth_token_THEN_client_can_be_authenticated_with_token() throws Exception {
        Configuration config = new Configuration(new Context());
        AuthHandler.registerAuthToken(new EvergreenService(config.lookupTopics(SERVICE_NAME)));
        Object authToken = config.find(SERVICE_NAME, "_UID").getOnce();

        assertNotNull(authToken);
        assertEquals(SERVICE_NAME, config.find(AUTH_TOKEN_LOOKUP_KEY, (String) authToken).getOnce());

        AuthHandler auth = new AuthHandler(config);
        RequestContext authContext = auth.doAuth(new FrameReader.Message(IPCUtil
                .encode(GeneralRequest.builder().type(AuthRequestTypes.Auth).request(authToken).build())));

        assertNotNull(authContext);
        assertEquals(SERVICE_NAME, authContext.serviceName);
    }

    @Test
    public void GIVEN_service_WHEN_try_to_authenticate_with_bad_token_THEN_is_rejected() throws Exception {
        Configuration config = new Configuration(new Context());

        AuthHandler auth = new AuthHandler(config);
        assertThrows(IPCClientNotAuthorizedException.class, () -> auth
                .doAuth(new FrameReader.Message(IPCUtil
                        .encode(GeneralRequest.builder().type(AuthRequestTypes.Auth).request("Bad Token").build()))));
    }
}
