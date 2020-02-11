/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.ipc.common.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.util.Log;
import io.netty.channel.Channel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Class for storing routing between IPC destination names and their handlers.
 */
@AllArgsConstructor
@NoArgsConstructor
public class IPCRouter {
    @Inject
    Log log;

    private final Map<Integer, IPCCallback> destinationCallbackMap = new ConcurrentHashMap<>();
    private final Map<ConnectionContext, Channel> clientToChannelMap = new ConcurrentHashMap<>();
    private final Map<ConnectionContext, List<Consumer<ConnectionContext>>> clientToDisconnectorsMap =
            new ConcurrentHashMap<>();
    private final Map<ClientAndRequestId, CompletableFuture<FrameReader.Message>> requestIdToCallbackMap =
            new ConcurrentHashMap<>();

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
        log.log(Log.Level.Note, "registering callback for destination ", destination);
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
     * Tries to get a connection handle from a context. The connection handle can be used to send
     * requests to the client and then wait for a response.
     *
     * @param connectionContext  The request context of the client that you want the handle to.
     * @param disconnectCallback Function to be called when the client disconnects. Use for cleaning up
     *                           extra listeners.
     * @return
     */
    @Nullable
    public ConnectionHandle getConnectionHandle(ConnectionContext connectionContext,
                                                Consumer<ConnectionContext> disconnectCallback) {
        Channel channel = clientToChannelMap.get(connectionContext);
        if (channel == null) {
            return null;
        }

        clientToDisconnectorsMap.compute(connectionContext, (key, value) -> {
            if (value == null) {
                value = new ArrayList<>();
            }
            value.add(disconnectCallback);
            return value;
        });

        return (destination, message) -> {
            FrameReader.MessageFrame requestFrame =
                    new FrameReader.MessageFrame(destination, message, FrameReader.FrameType.REQUEST);

            CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();
            requestIdToCallbackMap
                    .put(new ClientAndRequestId(requestFrame.requestId, connectionContext), fut);

            channel.writeAndFlush(requestFrame);

            return fut;
        };
    }

    /**
     * Only called by MessageRouter, do not call in any other place.
     * Called when a client disconnects from the server.
     *
     * @param context client which disconnected's context
     */
    public void clientDisconnected(ConnectionContext context) {
        clientToChannelMap.remove(context);
        List<Consumer<ConnectionContext>> disconnectors = clientToDisconnectorsMap.remove(context);
        if (disconnectors != null) {
            disconnectors.forEach(d -> d.accept(context));
        }
    }

    /**
     * Only called by MessageRouter, do not call in any other place.
     * Called when a client first authenticates successfully.
     *
     * @param context the context for the client that just connected
     * @param channel the channel to talk to the client
     */
    public void clientConnected(ConnectionContext context, Channel channel) {
        clientToChannelMap.put(context, channel);
    }

    /**
     * Only called by MessageRouter, do not call in any other place.
     * Called to handle a response type message coming into the server.
     * A request id listener should have already been registered.
     *
     * @param context the client context
     * @param message the incoming response message
     * @throws IPCException if no callback was registered for the request id
     */
    public void handleResponseMessage(ConnectionContext context, FrameReader.MessageFrame message) throws IPCException {
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
