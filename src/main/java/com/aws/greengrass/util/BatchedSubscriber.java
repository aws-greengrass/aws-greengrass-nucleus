/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.WhatHappened;

import java.util.Objects;
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
public final class BatchedSubscriber implements ChildChanged, Subscriber {

    private static final BiPredicate<WhatHappened, Node> BASE_EXCLUSION = (what, child) ->
            what == WhatHappened.timestampUpdated || what == WhatHappened.interiorAdded;

    private final AtomicInteger numRequestedChanges = new AtomicInteger();

    private final BiPredicate<WhatHappened, Node> exclusions;
    private final Callback callback;

    /**
     * Callback to perform after a batch of changes fires.
     */
    public interface Callback {
        /**
         * Perform the subscriber action.
         *
         * @param what {@link WhatHappened#initialized} on subscription initialization,
         *             otherwise a pass-through from the subscription.
         */
        void run(WhatHappened what);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param callback action to perform after a batch of changes
     */
    public BatchedSubscriber(Callback callback) {
        this((what, node) -> false, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param exclusions exclude changes based on what happened
     * @param callback   action to perform after a batch of changes
     */
    public BatchedSubscriber(BiPredicate<WhatHappened, Node> exclusions, Callback callback) {
        Objects.requireNonNull(exclusions);
        this.exclusions = (what, node) -> BASE_EXCLUSION.test(what, node) || exclusions.test(what, node);
        this.callback = callback;
    }

    @Override
    public void childChanged(WhatHappened what, Node child) {
        onChange(what, child);
    }

    @Override
    public void published(WhatHappened what, Topic t) {
        onChange(what, t);
    }

    private void onChange(WhatHappened what, Node child) {
        if (exclusions.test(what, child)) {
            return;
        }

        if (what == WhatHappened.initialized) {
            if (callback != null) {
                callback.run(what);
            }
            return;
        }

        numRequestedChanges.incrementAndGet();
        child.context.runOnPublishQueue(() -> {
            if (numRequestedChanges.decrementAndGet() == 0) {
                callback.run(what);
            }
        });
    }
}
