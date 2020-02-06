/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.util.Log;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Class for storing routing between IPC destination names and their handlers
 */
@AllArgsConstructor
@NoArgsConstructor
public class IPCRouter {
    @Inject
    Log log;

    private final ConcurrentHashMap<String, IPCCallback> destinationCallbackMap = new ConcurrentHashMap<>();

    /**
     * Registers a callback for a destination, Dispatcher will invoke the function for all message with registered
     * destination
     *
     * @param destination
     * @param callback    Function which takes a message and return a message. The function implementation needs to
     *                    the thread safe
     * @throws IPCException if the callback is already registered for a destination
     */
    public void registerServiceCallback(String destination, IPCCallback callback) throws IPCException {
        log.log(Log.Level.Note, "registering callback for destination ", destination);
        IPCCallback existingFunction = destinationCallbackMap.putIfAbsent(destination, callback);
        if (existingFunction != null) {
            throw new IPCException("callback for destination already registered");
        }
    }

    /**
     * Looks up the callback for a given destination.
     *
     * @param destination
     * @return destination callback (may be null)
     */
    @Nullable
    public IPCCallback getCallbackForDestination(String destination) {
        return destinationCallbackMap.get(destination);
    }
}
