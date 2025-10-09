/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;

/**
 * This Executor warrants task ordering for tasks with same key (key have to implement hashCode and equal methods
 * correctly).
 */
public class OrderedExecutorService implements Executor {
    private static final Logger log = LogManager.getLogger(OrderedExecutorService.class);
    private final Executor executor;
    @Getter(AccessLevel.PACKAGE)
    private final Map<Object, BlockingQueue<Runnable>> keyedOrderedTasks = new HashMap<>();

    @Inject
    public OrderedExecutorService(Executor executor) {
        this.executor = executor;
    }

    @Override
    public void execute(Runnable task) {
        // task without key can be executed immediately
        executor.execute(task);
    }

    /**
     * Executes the given command at some time in the future. The command may execute in a new thread, in a pooled
     * thread, or in the calling thread, at the discretion of the {@code Executor} implementation. The tasks with the
     * same key will run sequentially. If no key is provided, the task will executed without any ordering.
     *
     * @param task the runnable task
     * @param key The key by which to order the tasks.
     */
    public void execute(Runnable task, Object key) {
        if (key == null) { // if key is null, execute without ordering
            execute(task);
            return;
        }

        AtomicBoolean isFirst = new AtomicBoolean(false);
        Runnable orderedTask;
        synchronized (keyedOrderedTasks) {
            BlockingQueue<Runnable> dependencyQueue = keyedOrderedTasks.computeIfAbsent(key, o -> {
                isFirst.set(true);
                return new LinkedBlockingDeque<>();
            });
            orderedTask = new OrderedTask(task, dependencyQueue, key);
            if (!isFirst.get()) {
                dependencyQueue.add(orderedTask);
            }
        }

        // execute method can block, call it outside synchronize block
        if (isFirst.get()) {
            executor.execute(orderedTask);
        }
    }

    class OrderedTask implements Runnable {
        private final BlockingQueue<Runnable> runnables;
        private final Runnable task;
        private final Object key;

        public OrderedTask(Runnable task, BlockingQueue<Runnable> runnables, Object key) {
            this.task = task;
            this.runnables = runnables;
            this.key = key;
        }

        @SuppressWarnings("PMD.AvoidCatchingThrowable")
        @Override
        public void run() {
            try {
                task.run();
            } catch (Throwable e) {
                log.atError().cause(e).log("Error executing ordered task for key: {}", this.key);
            } finally {
                synchronized (keyedOrderedTasks) {
                    keyedOrderedTasks.computeIfPresent(key, (o, runnables) -> {
                        if (runnables.isEmpty()) {
                            return null;
                        }
                        Runnable runnable = this.runnables.poll();
                        if (runnable != null) {
                            executor.execute(runnable);
                        }
                        return runnables;
                    });
                }
            }
        }
    }
}
