/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.builtin.services.pubsub.PubSubIPCAgent;
import com.aws.greengrass.builtin.services.pubsub.PubSubIPCEventStreamAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.IPCRouter;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader.Message;
import com.aws.greengrass.ipc.exceptions.IPCException;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.pubsub.PubSubClientOpCodes;
import com.aws.greengrass.ipc.services.pubsub.PubSubGenericResponse;
import com.aws.greengrass.ipc.services.pubsub.PubSubPublishRequest;
import com.aws.greengrass.ipc.services.pubsub.PubSubResponseStatus;
import com.aws.greengrass.ipc.services.pubsub.PubSubSubscribeRequest;
import com.aws.greengrass.ipc.services.pubsub.PubSubUnsubscribeRequest;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.IPCRouter.DESTINATION_STRING;

public class PubSubIPCService implements Startable, InjectionActions {
    private static final Logger logger = LogManager.getLogger(PubSubIPCService.class);
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();
    public static final String PUB_SUB_SERVICE_NAME = "aws.greengrass.ipc.pubsub";

    @Inject
    private IPCRouter router;

    @Inject
    private PubSubIPCAgent agent;

    @Inject
    private AuthorizationHandler authorizationHandler;

    @Inject
    private PubSubIPCEventStreamAgent eventStreamAgent;

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Override
    public void postInject() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.PUBSUB;

        List<String> opCodes = Stream.of(PubSubClientOpCodes.values())
                .filter(c -> !c.equals(PubSubClientOpCodes.UNSUBSCRIBE))
                .map(PubSubClientOpCodes::name)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
        opCodes.add(GreengrassCoreIPCService.SUBSCRIBE_TO_TOPIC);
        opCodes.add(GreengrassCoreIPCService.PUBLISH_TO_TOPIC);
        try {
            authorizationHandler.registerComponent(PUB_SUB_SERVICE_NAME, new HashSet<>(opCodes));
        } catch (AuthorizationException e) {
            logger.atError("initialize-pubsub-authorization-error", e)
                    .kv(DESTINATION_STRING, destination.name())
                    .log("Failed to initialize the Pub/Sub service with the Authorization module.");
        }

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
        CompletableFuture<Message> fut = new CompletableFuture<>();

        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        try {
            // GG_NEEDS_REVIEW: TODO: add version compatibility check
            PubSubClientOpCodes opCode = PubSubClientOpCodes.values()[applicationMessage.getOpCode()];
            PubSubGenericResponse pubSubGenericResponse = new PubSubGenericResponse();
            switch (opCode) {
                case SUBSCRIBE:
                    PubSubSubscribeRequest subscribeRequest =
                            CBOR_MAPPER.readValue(applicationMessage.getPayload(), PubSubSubscribeRequest.class);
                    doAuthorization(opCode.toString(), context.getServiceName(), subscribeRequest.getTopic());
                    pubSubGenericResponse = agent.subscribe(subscribeRequest, context);
                    break;
                case PUBLISH:
                    PubSubPublishRequest publishRequest =
                            CBOR_MAPPER.readValue(applicationMessage.getPayload(), PubSubPublishRequest.class);
                    doAuthorization(opCode.toString(), context.getServiceName(), publishRequest.getTopic());
                    pubSubGenericResponse = agent.publish(publishRequest);
                    break;
                case UNSUBSCRIBE:
                    PubSubUnsubscribeRequest unsubscribeRequest =
                            CBOR_MAPPER.readValue(applicationMessage.getPayload(), PubSubUnsubscribeRequest.class);
                    doAuthorization(opCode.toString(), context.getServiceName(), unsubscribeRequest.getTopic());
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
        } catch (AuthorizationException e) {
            logger.atWarn().setEventType("pubsub-authorization-error").log(e.getMessage());
            try {
                PubSubGenericResponse response = new PubSubGenericResponse(PubSubResponseStatus.Unauthorized,
                        e.getMessage());
                ApplicationMessage responseMessage =
                        ApplicationMessage.builder().version(applicationMessage.getVersion())
                                .payload(CBOR_MAPPER.writeValueAsBytes(response)).build();
                fut.complete(new Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError("pubsub-authorization-error-response", ex)
                        .log("Failed to send authorization error response");
            }
        } catch (Throwable e) {
            logger.atError().setEventType("pubsub-error").setCause(e).log("Failed to handle message");
            try {
                PubSubGenericResponse response = new PubSubGenericResponse(PubSubResponseStatus.InternalError,
                        e.getMessage());
                ApplicationMessage responseMessage =
                        ApplicationMessage.builder().version(applicationMessage.getVersion())
                                .payload(CBOR_MAPPER.writeValueAsBytes(response)).build();
                fut.complete(new Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError("pubsub-error-response", ex).log("Failed to send error response");
            }
        }
        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }
        return fut;
    }

    private void doAuthorization(String opCode, String serviceName, String topic) throws AuthorizationException {
        authorizationHandler.isAuthorized(
                PUB_SUB_SERVICE_NAME,
                Permission.builder()
                        .principal(serviceName)
                        .operation(opCode.toLowerCase())
                        .resource(topic)
                        .build());
    }

    @Override
    public void startup() {
        greengrassCoreIPCService.setSubscribeToTopicHandler(
                context -> eventStreamAgent.getSubscribeToTopicHandler(context));
        greengrassCoreIPCService.setPublishToTopicHandler(
                context -> eventStreamAgent.getPublishToTopicHandler(context));
    }
}
