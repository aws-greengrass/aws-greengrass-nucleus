/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.ipc.exceptions.IPCException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class IPCRouterTest {

    @Test
    void GIVEN_function_WHEN_register_callback_THEN_callback_can_be_called() throws Throwable {
        IPCRouter router = new IPCRouter();

        CountDownLatch cdl = new CountDownLatch(1);
        router.registerServiceCallback(100, (a, b) -> {
            cdl.countDown();
            return null;
        });

        router.getCallbackForDestination(100).onMessage(null, null);
        assertTrue(cdl.await(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void GIVEN_already_registered_function_WHEN_register_callback_THEN_exception_is_thrown() throws Throwable {
        IPCRouter router = new IPCRouter();

        router.registerServiceCallback(100, (a, b) -> null);

        assertThrows(IPCException.class, () -> router.registerServiceCallback(100, (a, b) -> null));
    }
}
