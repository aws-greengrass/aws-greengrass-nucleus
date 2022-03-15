/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

/**
 * Utility class that allows a subscription to only fire
 * on the last change in a batch.
 *
 * <p>This is useful in scenarios such as configuration changes
 * where you may only want to perform expensive work once within
 * a short span.
 */
public final class BatchedSubscriber {

    private final Runnable unsubscribe;

    private BatchedSubscriber(Runnable unsubscribe) {
        this.unsubscribe = unsubscribe;
    }

    /**
     * Remove the subscription.
     */
    public void unsubscribe() {
        if (unsubscribe != null) {
            unsubscribe.run();
        }
    }

    /**
     * Subscribe to topic changes.
     *
     * @param topic      topic
     * @param afterBatch callback to perform after a batch of changes
     * @return batched subscriber
     */
    public static BatchedSubscriber subscribe(Topic topic, Runnable afterBatch) {
        return subscribe(topic, afterBatch, null, null);
    }

    /**
     * Subscribe to topic changes. Subscriptions will only be called once
     * for a batch of changes.
     *
     * @param topic            topic
     * @param afterBatch       callback to perform after a batch of changes
     * @param onInitialization callback to perform after the subscription has been added
     * @param excludeFilter    custom filter for ignoring changes
     * @return batched subscriber
     */
    public static BatchedSubscriber subscribe(Topic topic,
                                              Runnable afterBatch, Runnable onInitialization,
                                              BiPredicate<WhatHappened, Topic> excludeFilter) {
        AtomicInteger numRequestedChanges = new AtomicInteger();
        AtomicInteger totalCalls = new AtomicInteger();
        Subscriber subscriber = (what, child) -> {
            if (what == WhatHappened.initialized) {
                if (onInitialization != null) {
                    onInitialization.run();
                }
                return;
            }

            if (what == WhatHappened.timestampUpdated || what == WhatHappened.interiorAdded) {
                return;
            }

            if (excludeFilter != null && excludeFilter.test(what, child)) {
                return;
            }

            numRequestedChanges.incrementAndGet();
            topic.context.runOnPublishQueue(() -> {
                int c = numRequestedChanges.decrementAndGet();
                System.out.println("numChanges: " + c + ", totalCalls: " + totalCalls.incrementAndGet());
                if (c == 0) {
                    afterBatch.run();
                }
            });
        };
        topic.subscribe(subscriber);
        return new BatchedSubscriber(() -> topic.remove(subscriber));
    }

    /**
     * Subscribe to topic changes. Subscriptions will only be called once
     * for a batch of changes.
     *
     * @param topics     topics
     * @param afterBatch callback to perform after a batch of changes
     * @return batched subscriber
     */
    public static BatchedSubscriber subscribe(Topics topics, Runnable afterBatch) {
        return subscribe(topics, afterBatch, null, null);
    }

    /**
     * Subscribe to topic changes. Subscriptions will only be called once
     * for a batch of changes.
     *
     * @param topics           topics
     * @param afterBatch       callback to perform after a batch of changes
     * @param onInitialization callback to perform after the subscription has been added
     * @param excludeFilter    custom filter for ignoring changes
     * @return batched subscriber
     */
    public static BatchedSubscriber subscribe(Topics topics,
                                              Runnable afterBatch, Runnable onInitialization,
                                              BiPredicate<WhatHappened, Node> excludeFilter) {
        AtomicInteger numRequestedChanges = new AtomicInteger();
        ChildChanged subscriber = (what, child) -> {
            if (what == WhatHappened.initialized) {
                if (onInitialization != null) {
                    onInitialization.run();
                }
                return;
            }

            if (what == WhatHappened.timestampUpdated || what == WhatHappened.interiorAdded) {
                return;
            }

            if (excludeFilter != null && excludeFilter.test(what, child)) {
                return;
            }

            numRequestedChanges.incrementAndGet();
            topics.context.runOnPublishQueue(() -> {
                if (numRequestedChanges.decrementAndGet() == 0) {
                    afterBatch.run();
                }
            });
        };
        topics.subscribe(subscriber);
        return new BatchedSubscriber(() -> topics.remove(subscriber));
    }
}
