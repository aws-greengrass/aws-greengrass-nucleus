/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.builtin.services.lifecycle.LifecycleIPCEventStreamAgent;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractDeferComponentUpdateOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractPauseComponentOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractResumeComponentOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToComponentUpdatesOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractUpdateStateOperationHandler;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;


import java.util.function.Function;

import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class LifecycleIPCServiceTest {

    LifecycleIPCService lifecycleIPCService;

    @Mock
    private LifecycleIPCEventStreamAgent eventStreamAgent;

    @Mock
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Mock
    OperationContinuationHandlerContext mockContext;

    @Mock
    private AuthorizationHandler authorizationHandler;

    @BeforeEach
    public void setup() {
        lifecycleIPCService = new LifecycleIPCService();
        lifecycleIPCService.setEventStreamAgent(eventStreamAgent);
        lifecycleIPCService.setGreengrassCoreIPCService(greengrassCoreIPCService);
        lifecycleIPCService.setAuthorizationHandler(authorizationHandler);
    }

    @Test
    void testHandlersRegistered() {
        lifecycleIPCService.startup();
        ArgumentCaptor<Function> argumentCaptor = ArgumentCaptor.forClass(Function.class);

        verify(greengrassCoreIPCService).setUpdateStateHandler(argumentCaptor.capture());
        Function<OperationContinuationHandlerContext, GeneratedAbstractUpdateStateOperationHandler> updateHandler =
                (Function<OperationContinuationHandlerContext,
                        GeneratedAbstractUpdateStateOperationHandler>)argumentCaptor.getValue();
        updateHandler.apply(mockContext);
        verify(eventStreamAgent).getUpdateStateOperationHandler(mockContext);

        verify(greengrassCoreIPCService).setSubscribeToComponentUpdatesHandler(argumentCaptor.capture());
        Function<OperationContinuationHandlerContext, GeneratedAbstractSubscribeToComponentUpdatesOperationHandler>
                subsHandler = (Function<OperationContinuationHandlerContext,
                GeneratedAbstractSubscribeToComponentUpdatesOperationHandler>)argumentCaptor.getValue();
        subsHandler.apply(mockContext);
        verify(eventStreamAgent).getSubscribeToComponentUpdateHandler(mockContext);

        verify(greengrassCoreIPCService).setDeferComponentUpdateHandler(argumentCaptor.capture());
        Function<OperationContinuationHandlerContext, GeneratedAbstractDeferComponentUpdateOperationHandler> deferHandler =
                (Function<OperationContinuationHandlerContext,
                        GeneratedAbstractDeferComponentUpdateOperationHandler>)argumentCaptor.getValue();
        deferHandler.apply(mockContext);
        verify(eventStreamAgent).getDeferComponentHandler(mockContext);

        verify(greengrassCoreIPCService).setPauseComponentHandler(argumentCaptor.capture());
        Function<OperationContinuationHandlerContext, GeneratedAbstractPauseComponentOperationHandler> pauseHandler =
                (Function<OperationContinuationHandlerContext,
                        GeneratedAbstractPauseComponentOperationHandler>)argumentCaptor.getValue();
        pauseHandler.apply(mockContext);
        verify(eventStreamAgent).getPauseComponentHandler(mockContext);

        verify(greengrassCoreIPCService).setResumeComponentHandler(argumentCaptor.capture());
        Function<OperationContinuationHandlerContext, GeneratedAbstractResumeComponentOperationHandler> resumeHandler =
                (Function<OperationContinuationHandlerContext,
                        GeneratedAbstractResumeComponentOperationHandler>)argumentCaptor.getValue();
        resumeHandler.apply(mockContext);
        verify(eventStreamAgent).getResumeComponentHandler(mockContext);
    }
}
