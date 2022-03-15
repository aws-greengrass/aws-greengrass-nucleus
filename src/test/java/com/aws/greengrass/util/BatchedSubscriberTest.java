/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


@ExtendWith(GGExtension.class)
class BatchedSubscriberTest {

    private final Supplier<UpdateBehaviorTree> mergeBehavior = () ->
            new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, System.currentTimeMillis());

    @Test
    void GIVEN_subscribe_to_topic_WHEN_burst_of_events_THEN_callback_runs_once() throws Exception {
        Topic topic = Topic.of(new Context(), "topic", null);
        AtomicInteger numTimesCalled = new AtomicInteger();
        CountDownLatch testComplete = new CountDownLatch(1);

        BatchedSubscriber s = BatchedSubscriber.subscribe(topic, () -> {
            numTimesCalled.getAndIncrement();
            testComplete.countDown();
        });

        try {
            // For a consistent happy-path test, we ensure all config changes
            // are properly queued before batched subscriber does its work.
            // Otherwise, there's a race condition between how quickly
            // all changes reach the queue and when the publish queue processes
            // the first message.
            CountDownLatch waitForChangesToQueue = new CountDownLatch(1);
            topic.context.runOnPublishQueue(() -> {
                try {
                    waitForChangesToQueue.await();
                } catch (InterruptedException e) {
                    fail(e);
                }
            });

            IntStream.range(0, 10).forEach(topic::withValue);
            waitForChangesToQueue.countDown();

            assertTrue(testComplete.await(5L, TimeUnit.SECONDS));
            topic.context.waitForPublishQueueToClear();
        } finally {
            s.unsubscribe();
            topic.context.close();
        }

        assertEquals(1, numTimesCalled.get());
    }

    @Test
    void GIVEN_subscribe_to_topic_WHEN_separate_events_THEN_callback_runs_every_time() throws Exception {
        Topic topic = Topic.of(new Context(), "topic", null);

        final int expectedNumChanges = 10;

        AtomicInteger numTimesCalled = new AtomicInteger();
        CountDownLatch testComplete = new CountDownLatch(1);

        BatchedSubscriber s = BatchedSubscriber.subscribe(topic, () -> {
            if (numTimesCalled.incrementAndGet() >= expectedNumChanges) {
                testComplete.countDown();
            }
        });

        try {
            IntStream.range(0, expectedNumChanges).forEach(i -> {
                topic.withValue(i);
                topic.context.waitForPublishQueueToClear();
            });

            assertTrue(testComplete.await(5L, TimeUnit.SECONDS));
            topic.context.waitForPublishQueueToClear();
        } finally {
            s.unsubscribe();
            topic.context.close();
        }

        assertEquals(expectedNumChanges, numTimesCalled.get());
    }


    @Test
    void GIVEN_subscribe_to_topics_WHEN_burst_of_events_THEN_callback_runs_once() throws Exception {
        Topics topics = Topics.of(new Context(), "topic", null);
        AtomicInteger numTimesCalled = new AtomicInteger();
        CountDownLatch testComplete = new CountDownLatch(1);

        BatchedSubscriber s = BatchedSubscriber.subscribe(topics, () -> {
            numTimesCalled.getAndIncrement();
            testComplete.countDown();
        });

        try {
            // For a consistent happy-path test, we ensure all config changes
            // are properly queued before batched subscriber does its work.
            // Otherwise, there's a race condition between how quickly
            // all changes reach the queue and when the publish queue processes
            // the first message.
            CountDownLatch waitForChangesToQueue = new CountDownLatch(1);
            topics.context.runOnPublishQueue(() -> {
                try {
                    waitForChangesToQueue.await();
                } catch (InterruptedException e) {
                    fail(e);
                }
            });

            IntStream.range(0, 5).forEach(i ->
                    topics.updateFromMap(Utils.immutableMap("key", i), mergeBehavior.get())
            );
            waitForChangesToQueue.countDown();

            assertTrue(testComplete.await(5L, TimeUnit.SECONDS));
            topics.context.waitForPublishQueueToClear();
        } finally {
            s.unsubscribe();
            topics.context.close();
        }

        assertEquals(1, numTimesCalled.get());
    }

    @Test
    void GIVEN_subscribe_to_topics_WHEN_separate_events_THEN_callback_runs_every_time() throws Exception {
        Topics topics = Topics.of(new Context(), "topic", null);

        final int expectedNumChanges = 10;

        AtomicInteger numTimesCalled = new AtomicInteger();
        CountDownLatch testComplete = new CountDownLatch(1);

        BatchedSubscriber s = BatchedSubscriber.subscribe(topics, () -> {
            if (numTimesCalled.incrementAndGet() >= expectedNumChanges) {
                testComplete.countDown();
            }
        });

        try {
            IntStream.range(0, expectedNumChanges).forEach(i -> {
                topics.updateFromMap(Utils.immutableMap("key", i), mergeBehavior.get());
                topics.context.waitForPublishQueueToClear();
            });

            assertTrue(testComplete.await(5L, TimeUnit.SECONDS));
            topics.context.waitForPublishQueueToClear();
        } finally {
            s.unsubscribe();
            topics.context.close();
        }

        assertEquals(expectedNumChanges, numTimesCalled.get());
    }
}