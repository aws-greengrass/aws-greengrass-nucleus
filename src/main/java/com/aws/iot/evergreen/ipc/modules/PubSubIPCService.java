package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.auth.AuthorizationHandler;
import com.aws.iot.evergreen.auth.AuthorizationPolicy;
import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.builtin.services.pubsub.PubSubIPCAgent;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubClientOpCodes;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubGenericResponse;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubPublishRequest;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubResponseStatus;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubSubscribeRequest;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubUnsubscribeRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;

//TODO: see if this needs to be a GGService
@ImplementsService(name = "pubsub", autostart = true)
public class PubSubIPCService extends EvergreenService {
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();
    private static final String destinationString = "destination";

    @Inject
    private IPCRouter router;

    @Inject
    private PubSubIPCAgent agent;

    @Inject
    private AuthorizationHandler authorizationHandler;

    public PubSubIPCService(Topics c) {
        super(c);
    }

    private void updateAuthConfig(Node config) throws AuthorizationException {
        List<AuthorizationPolicy> authorizationPolicyList = AuthorizationPolicy
                .parseAuthorizationPolicy(config.parent);
        if (authorizationPolicyList != null) {
            authorizationHandler.loadAuthorizationPolicy(this.getName(), authorizationPolicyList);
        }
    }

    @Override
    public void postInject() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.PUBSUB;
        super.postInject();


        List<String> opCodes = Stream.of(PubSubClientOpCodes.values())
                .map(PubSubClientOpCodes::name)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
        try {
            authorizationHandler.registerService(this.getName(), new HashSet<String>(opCodes));
            //Initialize auth config
            updateAuthConfig(this.config);
        } catch (AuthorizationException e) {
            logger.atError().setEventType("ipc-initialize-pubsub-authorization-error").setCause(e)
                    .addKeyValue(destinationString, destination.name())
                    .log("Failed to initialize the Pub/Sub service with the Authorization module.");
        }

        //Subscribe to future auth config updates
        this.config.subscribe(
                (why, newv) -> {
                    if (newv == null) {
                        return;
                    }
                    try {
                        updateAuthConfig(newv);
                    } catch (AuthorizationException e) {
                        logger.atError().setEventType("ipc-load-authorization-config-error").setCause(e)
                                .addKeyValue(destinationString, destination.name())
                                .log("Failed to load the Pub/Sub Authorization config.");
                    }
                });


        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo().setEventType("ipc-register-request-handler")
                    .addKeyValue(destinationString, destination.name())
                    .log();
        } catch (IPCException e) {
            logger.atError().setEventType("ipc-register-request-handler-error").setCause(e)
                    .addKeyValue(destinationString, destination.name())
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
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Future<Message> handleMessage(Message message, ConnectionContext context) {
        CompletableFuture<Message> fut = new CompletableFuture<>();

        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        try {
            //TODO: add version compatibility check
            PubSubClientOpCodes opCode = PubSubClientOpCodes.values()[applicationMessage.getOpCode()];
            PubSubGenericResponse pubSubGenericResponse = new PubSubGenericResponse();
            switch (opCode) {
                case SUBSCRIBE:
                    PubSubSubscribeRequest subscribeRequest =
                            CBOR_MAPPER.readValue(applicationMessage.getPayload(), PubSubSubscribeRequest.class);
                    pubSubGenericResponse = agent.subscribe(subscribeRequest, context);
                    break;
                case PUBLISH:
                    PubSubPublishRequest readRequest =
                            CBOR_MAPPER.readValue(applicationMessage.getPayload(), PubSubPublishRequest.class);
                    pubSubGenericResponse = agent.publish(readRequest);
                    break;
                case UNSUBSCRIBE:
                    PubSubUnsubscribeRequest unsubscribeRequest =
                            CBOR_MAPPER.readValue(applicationMessage.getPayload(), PubSubUnsubscribeRequest.class);
                    pubSubGenericResponse = agent.unsubscribe(unsubscribeRequest, context);
                    break;
                default:
                    pubSubGenericResponse.setStatus(PubSubResponseStatus.InvalidRequest);
                    pubSubGenericResponse.setErrorMessage("Unknown request type " + opCode.toString());
                    break;
            }

            ApplicationMessage responseMessage = ApplicationMessage.builder().version(applicationMessage.getVersion())
                    .payload(CBOR_MAPPER.writeValueAsBytes(pubSubGenericResponse)).build();
            fut.complete(new Message(responseMessage.toByteArray()));
        } catch (Throwable e) {
            logger.atError().setEventType("pubsub-ipc-error").setCause(e).log("Failed to handle message");
            try {
                PubSubGenericResponse response =
                        new PubSubGenericResponse(PubSubResponseStatus.InternalError, e.getMessage());
                ApplicationMessage responseMessage =
                        ApplicationMessage.builder().version(applicationMessage.getVersion())
                                .payload(CBOR_MAPPER.writeValueAsBytes(response)).build();
                fut.complete(new Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError("pubsub-ipc-error", ex).log("Failed to send error response");
            }
        }
        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }
        return fut;
    }
}
