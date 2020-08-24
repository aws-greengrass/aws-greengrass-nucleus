package com.aws.iot.evergreen.ipc.modules;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.AuthenticationHandler;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.authorization.AuthorizationRequest;
import com.aws.iot.evergreen.ipc.authorization.AuthorizationResponse;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.exceptions.UnauthenticatedException;
import com.aws.iot.evergreen.ipc.exceptions.UnauthorizedException;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.IPCRouter.DESTINATION_STRING;

@ImplementsService(name = AuthorizationService.AUTHORIZATION_SERVICE_NAME, autostart = true)
public class AuthorizationService extends EvergreenService {
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();
    public static final String AUTHORIZATION_SERVICE_NAME = "aws.greengrass.ipc.authorization";

    @Inject
    private IPCRouter router;

    @Inject
    private AuthenticationHandler authenticationHandler;

    public AuthorizationService(Topics topics) {
        super(topics);
    }

    @Override
    public void postInject() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.AUTHORIZATION;
        super.postInject();

        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo("register-request-handler")
                    .kv(DESTINATION_STRING, destination.name())
                    .log();
        } catch (IPCException e) {
            logger.atError("register-request-handler-error", e)
                    .kv(DESTINATION_STRING, destination.name())
                    .log("Failed to register service callback to destination");
        }
    }

    /**
     * Handle all requests from the client.
     *
     * @param message the incoming request
     * @param context caller request context
     * @return future containing our response
     */
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.AvoidInstanceofChecksInCatchClause"})
    public Future<Message> handleMessage(Message message, ConnectionContext context) {

        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        CompletableFuture<Message> fut = new CompletableFuture<>();
        AuthorizationResponse response;
        try {
            AuthorizationRequest request = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                    AuthorizationRequest.class);

            //This will throw an UnauthorizedException if not authenticated
            doAuthorize(request);
            response = new AuthorizationResponse(true, null);

            ApplicationMessage responseMessage = ApplicationMessage.builder()
                    .version(applicationMessage.getVersion())
                    .payload(CBOR_MAPPER.writeValueAsBytes(response))
                    .build();
            fut.complete(new Message(responseMessage.toByteArray()));

        } catch (Throwable e) {
            logger.atError("authorization-error", e).log("Failed to handle message");
            try {
                response = new AuthorizationResponse(false, e.toString());
                ApplicationMessage responseMessage = ApplicationMessage.builder()
                        .version(applicationMessage.getVersion())
                        .payload(CBOR_MAPPER.writeValueAsBytes(response))
                        .build();
                fut.complete(new Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError("authorization-error", ex).log("Failed to send error response");
            }
        }

        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }
        return fut;
    }

    private void doAuthorize(AuthorizationRequest request) throws UnauthorizedException {
        try {
            authenticationHandler.doAuthentication(request.getToken());
        } catch (UnauthenticatedException e) {
            throw new UnauthorizedException("Unable to authorize request", e);
        }

    }
}
