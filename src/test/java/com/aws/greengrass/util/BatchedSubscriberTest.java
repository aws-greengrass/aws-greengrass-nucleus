/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
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
    void GIVEN_subscribe_to_topic_WHEN_unsubscribe_THEN_subscription_not_invoked() throws Exception {
        Topic topic = Topic.of(new Context(), "topic", null);

        AtomicInteger numTimesCalled = new AtomicInteger();

        BatchedSubscriber bs = new BatchedSubscriber(topic, numTimesCalled::incrementAndGet);
        bs.subscribe();

        try {
            waitForChangesToQueue(topic, () -> IntStream.range(0, 10).forEach(topic::withValue));
            topic.context.waitForPublishQueueToClear();
            bs.unsubscribe();
            waitForChangesToQueue(topic, () -> IntStream.range(0, 10).forEach(topic::withValue));
            topic.context.waitForPublishQueueToClear();
        } finally {
            topic.context.close();
        }

        assertEquals(1, numTimesCalled.get());
    }

    @Test
    void GIVEN_subscribe_to_topic_WHEN_exclusion_specified_THEN_changes_are_excluded() throws Exception {
        Topic topic = Topic.of(new Context(), "topic", null);

        BiPredicate<WhatHappened, Node> excludeEverything = (what, child) -> true;

        AtomicInteger numTimesCalled = new AtomicInteger();
        BatchedSubscriber bs = new BatchedSubscriber(topic, excludeEverything, numTimesCalled::incrementAndGet);
        bs.subscribe();

        try {
            waitForChangesToQueue(topic, () -> IntStream.range(0, 10).forEach(topic::withValue));
            topic.context.waitForPublishQueueToClear();
        } finally {
            bs.unsubscribe();
            topic.context.close();
        }

        assertEquals(0, numTimesCalled.get());
    }

    @Test
    void GIVEN_subscribe_to_topic_WHEN_burst_of_events_THEN_callback_runs_once() throws Exception {
        Topic topic = Topic.of(new Context(), "topic", null);

        AtomicInteger numTimesCalled = new AtomicInteger();
        CountDownLatch testComplete = new CountDownLatch(1);

        BatchedSubscriber bs = new BatchedSubscriber(topic, () -> {
            numTimesCalled.getAndIncrement();
            testComplete.countDown();
        });
        bs.subscribe();

        try {
            waitForChangesToQueue(topic, () -> IntStream.range(0, 10).forEach(topic::withValue));
            assertTrue(testComplete.await(5L, TimeUnit.SECONDS));
            topic.context.waitForPublishQueueToClear();
        } finally {
            bs.unsubscribe();
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

        BatchedSubscriber bs = new BatchedSubscriber(topic, () -> {
            if (numTimesCalled.incrementAndGet() >= expectedNumChanges) {
                testComplete.countDown();
            }
        });
        bs.subscribe();

        try {
            IntStream.range(0, expectedNumChanges).forEach(i -> {
                topic.withValue(i);
                topic.context.waitForPublishQueueToClear();
            });

            assertTrue(testComplete.await(5L, TimeUnit.SECONDS));
            topic.context.waitForPublishQueueToClear();
        } finally {
            bs.unsubscribe();
            topic.context.close();
        }

        assertEquals(expectedNumChanges, numTimesCalled.get());
    }


    @Test
    void GIVEN_subscribe_to_topics_WHEN_burst_of_events_THEN_callback_runs_once() throws Exception {
        Topics topics = Topics.of(new Context(), "topic", null);

        AtomicInteger numTimesCalled = new AtomicInteger();
        CountDownLatch testComplete = new CountDownLatch(1);

        BatchedSubscriber bs = new BatchedSubscriber(topics, () -> {
            numTimesCalled.getAndIncrement();
            testComplete.countDown();
        });
        bs.subscribe();

        try {
            waitForChangesToQueue(topics, () -> IntStream.range(0, 5).forEach(i ->
                    topics.updateFromMap(Utils.immutableMap("key", i), mergeBehavior.get())
            ));
            assertTrue(testComplete.await(5L, TimeUnit.SECONDS));
            topics.context.waitForPublishQueueToClear();
        } finally {
            bs.unsubscribe();
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

        BatchedSubscriber bs = new BatchedSubscriber(topics, () -> {
            if (numTimesCalled.incrementAndGet() >= expectedNumChanges) {
                testComplete.countDown();
            }
        });
        bs.subscribe();

        try {
            IntStream.range(0, expectedNumChanges).forEach(i -> {
                topics.updateFromMap(Utils.immutableMap("key", i), mergeBehavior.get());
                topics.context.waitForPublishQueueToClear();
            });

            assertTrue(testComplete.await(5L, TimeUnit.SECONDS));
            topics.context.waitForPublishQueueToClear();
        } finally {
            bs.unsubscribe();
            topics.context.close();
        }

        assertEquals(expectedNumChanges, numTimesCalled.get());
    }

    /**
     * For a consistent test scenario, we ensure all config changes
     * are properly queued before batched subscriber does its work.
     *
     * @param node         topic or topics
     * @param queueChanges action that modifies the provided topic(s)
     */
    private void waitForChangesToQueue(Node node, Runnable queueChanges) {
        CountDownLatch waitForChangesToQueue = new CountDownLatch(1);
        node.context.runOnPublishQueue(() -> {
            try {
                waitForChangesToQueue.await();
            } catch (InterruptedException e) {
                fail(e);
            }
        });
        queueChanges.run();
        waitForChangesToQueue.countDown();
    }
}
