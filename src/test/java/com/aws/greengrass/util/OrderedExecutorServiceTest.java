/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class OrderedExecutorServiceTest {
    private static OrderedExecutorService orderedExecutorService;
    private volatile static Throwable lastThrownException = null;
    private static ExecutorService executor;

    @BeforeAll
    static void startUp() {
        executor = Executors.newCachedThreadPool();
        orderedExecutorService = new OrderedExecutorService(executor);
    }

    @AfterAll
    static void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void GIVEN_multiple_keys_and_tasks_WHEN_esecute_THEN_executes_in_proper_order_for_each_key()
            throws InterruptedException {
        String key = UUID.randomUUID().toString();
        String key2 = UUID.randomUUID().toString();

        Queue<String> queue = new LinkedList<>();
        List<Runnable> tasks = new LinkedList<>();
        for (int i = 0; i < 100; i++) {
            String randomString = UUID.randomUUID().toString();
            queue.add(randomString);
            tasks.add(createRunnable(randomString, queue));
        }
        Queue<String> queue2 = new LinkedList<>();
        List<Runnable> tasks2 = new LinkedList<>();
        for (int i = 0; i < 100; i++) {
            String randomString = UUID.randomUUID().toString();
            queue2.add(randomString);
            tasks2.add(createRunnable(randomString, queue2));
        }

        for (int i = 0; i < 100; i++) {
            orderedExecutorService.execute(tasks.get(i), key);
            orderedExecutorService.execute(tasks2.get(i), key2);
        }
        while (!orderedExecutorService.getKeyedOrderedTasks().isEmpty()) {
            TimeUnit.SECONDS.sleep(1);
        }

        if(lastThrownException != null) {
            fail(lastThrownException.getMessage(), lastThrownException);
        }
    }

    private Runnable createRunnable(final String randomStringToCheck, final Queue<String> queue){
        return () -> {
            String firstRandomVarFromQueue = queue.poll();
            try {
                assertEquals(randomStringToCheck, firstRandomVarFromQueue);
            } catch (AssertionError e) {
                lastThrownException = e;
            }
        };
    }
}
