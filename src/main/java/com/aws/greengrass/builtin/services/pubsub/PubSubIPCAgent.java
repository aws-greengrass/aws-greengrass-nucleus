/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.common.IPCUtil;
import com.aws.greengrass.ipc.services.pubsub.MessagePublishedEvent;
import com.aws.greengrass.ipc.services.pubsub.PubSubGenericResponse;
import com.aws.greengrass.ipc.services.pubsub.PubSubImpl;
import com.aws.greengrass.ipc.services.pubsub.PubSubPublishRequest;
import com.aws.greengrass.ipc.services.pubsub.PubSubResponseStatus;
import com.aws.greengrass.ipc.services.pubsub.PubSubServiceOpCodes;
import com.aws.greengrass.ipc.services.pubsub.PubSubSubscribeRequest;
import com.aws.greengrass.ipc.services.pubsub.PubSubUnsubscribeRequest;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.DefaultConcurrentHashMap;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.inject.Inject;

/**
 * Class to handle business logic for all PubSub requests over IPC.
 */
public class PubSubIPCAgent {
    // Map from connection --> Function to call for each published message
    private static final Map<String, Set<Object>> listeners = new DefaultConcurrentHashMap<>(CopyOnWriteArraySet::new);
    private static final int TIMEOUT_SECONDS = 30;

    private final ExecutorService executor;

    private static final Logger log = LogManager.getLogger(PubSubIPCAgent.class);

    @Inject
    public PubSubIPCAgent(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Publish a message to all subscribers.
     *
     * @param publishRequest publish request
     * @return response
     */
    public PubSubGenericResponse publish(PubSubPublishRequest publishRequest) {
        if (!listeners.containsKey(publishRequest.getTopic())) {
            // Still technically successful, just no one was subscribed
            return new PubSubGenericResponse(PubSubResponseStatus.Success, null);
        }
        Set<Object> contexts = listeners.get(publishRequest.getTopic());

        executor.execute(() -> {
            contexts.forEach(c -> {
                publishToTopic(new MessagePublishedEvent(publishRequest.getTopic(), publishRequest.getPayload()), c);
            });
        });
        return new PubSubGenericResponse(PubSubResponseStatus.Success, null);
    }

    /**
     * Handle the subscription request from the user.
     *
     * @param subscribeRequest subscribe request
     * @param context          connection context
     * @return response code Success if all went well
     */
    public PubSubGenericResponse subscribe(PubSubSubscribeRequest subscribeRequest, ConnectionContext context) {
        // GG_NEEDS_REVIEW: TODO: Input validation. https://sim.amazon.com/issues/P32540011
        log.debug("Subscribing to topic {}, {}", subscribeRequest.getTopic(), context);
        listeners.get(subscribeRequest.getTopic()).add(context);
        context.onDisconnect(() -> {
            if (listeners.containsKey(subscribeRequest.getTopic()) && listeners.get(subscribeRequest.getTopic())
                    .remove(context)) {
                log.debug("Client {} disconnected, removing subscription {}", context, subscribeRequest.getTopic());
            }
        });

        return new PubSubGenericResponse(PubSubResponseStatus.Success, null);
    }

    /**
     * Handle the subscription request from the user.
     *
     * @param subscribeRequest subscribe request
     * @param cb               callback to be called for each published message
     */
    public void subscribe(PubSubSubscribeRequest subscribeRequest, Consumer<MessagePublishedEvent> cb) {
        // GG_NEEDS_REVIEW: TODO: Input validation. https://sim.amazon.com/issues/P32540011
        log.debug("Subscribing to topic {}", subscribeRequest.getTopic());
        listeners.get(subscribeRequest.getTopic()).add(cb);
    }

    /**
     * Unsubscribe a client from a topic.
     *
     * @param unsubscribeRequest request containing the topic to unsubscribe from
     * @param context            client to unsubscribe
     * @return response
     */
    public PubSubGenericResponse unsubscribe(PubSubUnsubscribeRequest unsubscribeRequest, ConnectionContext context) {
        log.debug("Unsubscribing from topic {}, {}", unsubscribeRequest.getTopic(), context);
        if (listeners.containsKey(unsubscribeRequest.getTopic())) {
            listeners.get(unsubscribeRequest.getTopic()).remove(context);
            return new PubSubGenericResponse(PubSubResponseStatus.Success, null);
        }
        return new PubSubGenericResponse(PubSubResponseStatus.TopicNotFound, "Topic not found");
    }

    /**
     * Unsubscribe from a topic.
     *
     * @param unsubscribeRequest request containing the topic to unsubscribe from
     * @param cb                 callback to remove from subscription
     */
    public void unsubscribe(PubSubUnsubscribeRequest unsubscribeRequest, Consumer<MessagePublishedEvent> cb) {
        log.debug("Unsubscribing from topic {}", unsubscribeRequest.getTopic());
        if (listeners.containsKey(unsubscribeRequest.getTopic())) {
            listeners.get(unsubscribeRequest.getTopic()).remove(cb);
        }
    }

    private void publishToTopic(MessagePublishedEvent event, Object sendTo) {
        if (sendTo instanceof ConnectionContext) {
            ConnectionContext context = (ConnectionContext) sendTo;

            log.atDebug().log("Sending publish event {} to {}", event, context);

            try {
                ApplicationMessage applicationMessage = ApplicationMessage.builder().version(PubSubImpl.API_VERSION)
                        .opCode(PubSubServiceOpCodes.PUBLISHED.ordinal()).payload(IPCUtil.encode(event)).build();
                // GG_NEEDS_REVIEW: TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                Future<FrameReader.Message> fut = context.serverPush(BuiltInServiceDestinationCode.PUBSUB.getValue(),
                        new FrameReader.Message(applicationMessage.toByteArray()));

                try {
                    fut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    // GG_NEEDS_REVIEW: TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    // Log
                    log.atError("error-sending-pubsub-update").kv("context", context)
                            .log("Error sending pubsub update to client", e);
                }
            } catch (IOException e) {
                // Log
                log.atError("error-sending-pubsub-update").kv("context", context)
                        .log("Error sending pubsub update to client", e);
            }
        } else if (sendTo instanceof Consumer) {
            ((Consumer<MessagePublishedEvent>) sendTo).accept(event);
        }
    }
}
