/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.common;

import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.common.IPCUtil;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Inject;

@NoArgsConstructor
@AllArgsConstructor
public class ServiceEventHelper {
    private static final Logger log = LogManager.getLogger(ServiceEventHelper.class);
    private static final int TIMEOUT_SECONDS = 30;

    @Inject
    private ExecutorService executor;

    /**
     * Asynchronously sends requested event to requested IPC channel.
     *
     * @param connectionContext client connection context
     * @param serviceEvent      event data
     * @param destinationCode   service destination code to denote the source of the event
     * @param opCode            op code
     * @param apiVersion        api version
     * @return Future for send event task
     */
    public Future<?> sendServiceEvent(ConnectionContext connectionContext, ServiceEvent serviceEvent,
                                      BuiltInServiceDestinationCode destinationCode, int opCode, int apiVersion) {
        return executor.submit(() -> {
            try {
                ApplicationMessage applicationMessage = ApplicationMessage.builder().version(apiVersion).opCode(opCode)
                        .payload(IPCUtil.encode(serviceEvent)).build();

                // GG_NEEDS_REVIEW: TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                Future<FrameReader.Message> fut = connectionContext.serverPush(destinationCode.getValue(),
                        new FrameReader.Message(applicationMessage.toByteArray()));
                try {
                    fut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    // GG_NEEDS_REVIEW: TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    // Log
                    log.atError("error-sending-configstore-update").kv("context", connectionContext)
                            .log("Error sending config store update to client", e);
                }
            } catch (IOException e) {
                // Log
                log.atError("error-sending-configstore-update").kv("context", connectionContext)
                        .log("Error sending config store update to client", e);
            }
        });
    }
}
