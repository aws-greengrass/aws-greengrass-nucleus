/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.ServiceInBrokenStateAfterDeploymentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class mergeTest {

    private EvergreenService mockMainService;
    private EvergreenService mockServiceA;
    private EvergreenService mockServiceB;

    @BeforeEach
    public void setup() {
        mockMainService = mock(EvergreenService.class);
        mockServiceA = mock(EvergreenService.class);
        mockServiceB = mock(EvergreenService.class);
    }

    @Test
    public void testSomeMethod() throws Exception {
        Configuration c = new Configuration(new Context());
        c.read(Kernel.class.getResource("config.yaml"), false);
        Configuration b = new Configuration(new Context()).copyFrom(c);
        assertEquals(c.getRoot(), b.getRoot());
    }

    @Test
    public void GIVEN_deployment_WHEN_all_service_are_running_THEN_waitForServicesToStart_complete_without_exception()
            throws InterruptedException {
        Kernel kernel = new Kernel();
        when(mockMainService.getState()).thenReturn(State.RUNNING);
        when(mockServiceA.getState()).thenReturn(State.RUNNING);
        when(mockServiceB.getState()).thenReturn(State.RUNNING);

        when(mockMainService.reachedDesiredState()).thenReturn(true);
        when(mockServiceA.reachedDesiredState()).thenReturn(true);
        when(mockServiceB.reachedDesiredState()).thenReturn(true);
        CompletableFuture future = new CompletableFuture();
        Set<EvergreenService> evergreenServices =
                new HashSet(Arrays.asList(mockMainService, mockServiceA, mockServiceB));
        kernel.waitForServicesToStart(evergreenServices, future);

        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    public void GIVEN_deployment_WHEN_one_service_is_broken_THEN_waitForServicesToStart_completes_Exceptionally()
            throws InterruptedException {
        Kernel kernel = new Kernel();
        when(mockMainService.getState()).thenReturn(State.BROKEN);
        when(mockServiceA.getState()).thenReturn(State.RUNNING);
        when(mockServiceB.getState()).thenReturn(State.RUNNING);

        when(mockMainService.reachedDesiredState()).thenReturn(true);
        when(mockServiceA.reachedDesiredState()).thenReturn(true);
        when(mockServiceB.reachedDesiredState()).thenReturn(true);
        CompletableFuture future = new CompletableFuture();
        Set<EvergreenService> evergreenServices =
                new HashSet(Arrays.asList(mockMainService, mockServiceA, mockServiceB));
        kernel.waitForServicesToStart(evergreenServices, future);

        future.whenComplete((v, t) -> {
            assertTrue(t instanceof ServiceInBrokenStateAfterDeploymentException);
            EvergreenService brokenService = ((ServiceInBrokenStateAfterDeploymentException) t).getBrokenService();
            assertEquals(mockMainService, brokenService);
        });
    }

}
