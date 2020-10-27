/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.common;

import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.common.IPCUtil;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreImpl;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreServiceOpCodes;
import com.aws.greengrass.ipc.services.configstore.ConfigurationUpdateEvent;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ServiceEventHelperTest {

    @Mock
    private ConnectionContext connectionContext;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ServiceEventHelper serviceEventHelper = new ServiceEventHelper(executor);

    @AfterEach
    void after() {
        executor.shutdownNow();
    }

    @Test
    void GIVEN_running_WHEN_send_event_called_THEN_send_event_to_client()
            throws ExecutionException, InterruptedException, IOException {
        CompletableFuture serverPushFuture = new CompletableFuture();
        serverPushFuture.complete(mock(FrameReader.Message.class));
        when(connectionContext.serverPush(anyInt(), any(FrameReader.Message.class))).thenReturn(serverPushFuture);

        ConfigurationUpdateEvent eventToSend =
                ConfigurationUpdateEvent.builder().componentName("SomeService").changedKeyPath(
                        Collections.singletonList("SomeKey")).build();
        serviceEventHelper.sendServiceEvent(connectionContext, eventToSend, BuiltInServiceDestinationCode.CONFIG_STORE,
                ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), ConfigStoreImpl.API_VERSION).get();

        ArgumentCaptor<FrameReader.Message> messageArgumentCaptor = ArgumentCaptor.forClass(FrameReader.Message.class);
        verify(connectionContext)
                .serverPush(eq(BuiltInServiceDestinationCode.CONFIG_STORE.getValue()), messageArgumentCaptor.capture());
        ApplicationMessage message = ApplicationMessage.fromBytes(messageArgumentCaptor.getValue().getPayload());
        assertEquals(ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), message.getOpCode());
        ConfigurationUpdateEvent eventSent = IPCUtil.decode(message.getPayload(), ConfigurationUpdateEvent.class);
        assertEquals(eventToSend, eventSent);
    }

}
