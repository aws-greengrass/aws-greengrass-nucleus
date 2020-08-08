package com.aws.iot.evergreen.ipc.common;

import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreImpl;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
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
    ExecutorService executor;

    /**
     * Send requested event to requested IPC channel.
     *
     * @param connectionContext client connection context
     * @param serviceEvent event data
     * @param destinationCode service destination code to denote the source of the event
     * @param opCode op code
     */
    public void sendServiceEvent(ConnectionContext connectionContext, ServiceEvent serviceEvent,
                                 BuiltInServiceDestinationCode destinationCode, int opCode) {
        try {
            ApplicationMessage applicationMessage =
                    ApplicationMessage.builder().version(ConfigStoreImpl.API_VERSION).opCode(opCode)
                            .payload(IPCUtil.encode(serviceEvent)).build();
            // TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
            Future<FrameReader.Message> fut = connectionContext
                    .serverPush(destinationCode.getValue(), new FrameReader.Message(applicationMessage.toByteArray()));
            // call the blocking "get" in a separate thread so we don't block the publish queue
            executor.execute(() -> {
                try {
                    fut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    // TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    // Log
                    log.atError("error-sending-configstore-update").kv("context", connectionContext)
                            .log("Error sending config store update to client", e);
                }
            });

        } catch (IOException e) {
            // Log
            log.atError("error-sending-configstore-update").kv("context", connectionContext)
                    .log("Error sending config store update to client", e);
        }
    }
}
