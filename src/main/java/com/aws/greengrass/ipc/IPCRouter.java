/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.exceptions.IPCException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import io.netty.channel.Channel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import javax.annotation.Nullable;

/**
 * Class for storing routing between IPC destination names and their handlers.
 */
@AllArgsConstructor
public class IPCRouter {
    private final Map<Integer, IPCCallback> destinationCallbackMap = new ConcurrentHashMap<>();
    private final Map<ConnectionContext, Channel> clientToChannelMap = new ConcurrentHashMap<>();
    private final Map<ClientAndRequestId, CompletableFuture<FrameReader.Message>> requestIdToCallbackMap =
            new ConcurrentHashMap<>();

    private static final Logger logger = LogManager.getLogger(IPCRouter.class);
    public static final String DESTINATION_STRING = "destination";

    /**
     * Registers a callback for a destination, Dispatcher will invoke the function for all message with registered
     * destination.
     *
     * @param destination destination name to register
     * @param callback    Function which takes a message and return a message. The function implementation needs to
     *                    the thread safe
     * @throws IPCException if the callback is already registered for a destination
     */
    public void registerServiceCallback(int destination, IPCCallback callback) throws IPCException {
        IPCCallback existingFunction = destinationCallbackMap.putIfAbsent(destination, callback);
        if (existingFunction != null) {
            throw new IPCException("callback for destination already registered");
        }
    }

    /**
     * Looks up the callback for a given destination.
     *
     * @param destination destination to lookup
     * @return destination callback (may be null)
     */
    @Nullable
    public IPCCallback getCallbackForDestination(int destination) {
        return destinationCallbackMap.get(destination);
    }

    /**
     * Send a message to a connection's destination and get a future for the response message.
     *
     * @param connection  connection to send the message to
     * @param destination destination within the context to target
     * @param msg         message to be send
     * @return future containing response message or exception
     */
    public Future<FrameReader.Message> sendAndReceive(ConnectionContext connection, int destination,
                                                      FrameReader.Message msg) {
        CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();

        Channel channel = clientToChannelMap.get(connection);
        if (channel == null) {
            fut.completeExceptionally(new IPCException("Channel not found for given connection context"));
            return fut;
        }

        FrameReader.MessageFrame requestFrame =
                new FrameReader.MessageFrame(destination, msg, FrameReader.FrameType.REQUEST);
        requestIdToCallbackMap.put(new ClientAndRequestId(requestFrame.requestId, connection), fut);

        channel.writeAndFlush(requestFrame);
        return fut;
    }

    /**
     * Called when a client disconnects from the server.
     *
     * @param context client which disconnected's context
     */
    private void clientDisconnected(ConnectionContext context) {
        clientToChannelMap.remove(context);
    }

    /**
     * Called when a client first authenticates successfully.
     *
     * @param context the context for the client that just connected
     * @param channel the channel to talk to the client
     */
    void clientConnected(ConnectionContext context, Channel channel) {
        clientToChannelMap.put(context, channel);
        context.onDisconnect(() -> clientDisconnected(context));
    }

    /**
     * Called to handle a response type message coming into the server.
     * A request id listener should have already been registered.
     *
     * @param context the client context
     * @param message the incoming response message
     * @throws IPCException if no callback was registered for the request id
     */
    void handleResponseMessage(ConnectionContext context, FrameReader.MessageFrame message) throws IPCException {
        CompletableFuture<FrameReader.Message> fut =
                requestIdToCallbackMap.get(new ClientAndRequestId(message.requestId, context));
        if (fut == null) {
            throw new IPCException(
                    "Callback not found for incoming response message with request id " + message.requestId);
        }
        fut.complete(message.message);
    }


    /**
     * Just having a mapping from request id to callback isn't good enough because our request ids
     * aren't random. This ensures that we map the response back to the correct listener based on what
     * connection was used to send the request to begin with.
     */
    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    private static class ClientAndRequestId {
        private final int requestId;
        private final ConnectionContext context;
    }
}
