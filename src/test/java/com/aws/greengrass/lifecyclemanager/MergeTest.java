/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(GGExtension.class)
class MergeTest {

    private GreengrassService mockMainService;
    private GreengrassService mockServiceA;
    private GreengrassService mockServiceB;
    private Kernel kernel;

    @BeforeEach
    void setup() {
        kernel = mock(Kernel.class);
        mockMainService = mock(GreengrassService.class);
        mockServiceA = mock(GreengrassService.class);
        mockServiceB = mock(GreengrassService.class);
    }

    @Test
    void testSomeMethod() throws Exception {
        try (Context context = new Context()) {
            Configuration c = new Configuration(context);
            c.read(Kernel.class.getResource("config.yaml"), false);
            Configuration b = new Configuration(context).copyFrom(c);
            assertEquals(c.getRoot(), b.getRoot());
        }
    }

    // GG_NEEDS_REVIEW: TODO : following tests need to go into the unit tests for DeploymentConfigMerger class when we
    // add it
    // and should be tested through the available method mergeNewConfig instead
    @Test
    void GIVEN_deployment_WHEN_all_service_are_running_THEN_waitForServicesToStart_completes_without_exception()
            throws Exception {
        when(mockMainService.getState()).thenReturn(State.RUNNING);
        when(mockServiceA.getState()).thenReturn(State.RUNNING);
        when(mockServiceB.getState()).thenReturn(State.RUNNING);

        when(mockMainService.reachedDesiredState()).thenReturn(true);
        when(mockServiceA.reachedDesiredState()).thenReturn(true);
        when(mockServiceB.reachedDesiredState()).thenReturn(true);
        Set<GreengrassService> greengrassServices =
                new HashSet<>(Arrays.asList(mockMainService, mockServiceA, mockServiceB));
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();
        DeploymentConfigMerger.waitForServicesToStart(greengrassServices, System.currentTimeMillis(),
                kernel, future);
        assertFalse(future.isDone());
    }

    @Test
    void GIVEN_deployment_WHEN_one_service_is_broken_THEN_waitForServicesToStart_completes_Exceptionally() {
        long curTime = System.currentTimeMillis();
        when(mockMainService.getState()).thenReturn(State.BROKEN);
        when(mockMainService.getStateModTime()).thenReturn(curTime);
        when(mockMainService.getName()).thenReturn("main");
        when(mockServiceA.getState()).thenReturn(State.RUNNING);
        when(mockServiceB.getState()).thenReturn(State.RUNNING);

        when(mockMainService.reachedDesiredState()).thenReturn(true);
        when(mockServiceA.reachedDesiredState()).thenReturn(true);
        when(mockServiceB.reachedDesiredState()).thenReturn(true);
        Set<GreengrassService> greengrassServices =
                new HashSet<>(Arrays.asList(mockMainService, mockServiceA, mockServiceB));
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        ServiceUpdateException ex = assertThrows(ServiceUpdateException.class,
                () -> DeploymentConfigMerger.waitForServicesToStart(greengrassServices, curTime - 10L, kernel, future));

        assertEquals("Service main in broken state after deployment", ex.getMessage());
        assertFalse(future.isDone());
    }

}
