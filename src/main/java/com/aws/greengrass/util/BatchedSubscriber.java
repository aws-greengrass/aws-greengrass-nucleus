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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

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

    private final Node node;
    private final AtomicInteger numRequestedChanges = new AtomicInteger();

    private final BiPredicate<WhatHappened, Node> exclusions;
    private final Consumer<WhatHappened> callback;

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param topic    topic to subscribe to
     * @param callback action to perform after a batch of changes and on initialization
     */
    public BatchedSubscriber(Topic topic, Consumer<WhatHappened> callback) {
        this(topic, null, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param topic      topic to subscribe to
     * @param exclusions predicate for ignoring a subset topic changes
     * @param callback   action to perform after a batch of changes and on initialization
     */
    public BatchedSubscriber(Topic topic, BiPredicate<WhatHappened, Node> exclusions,
                             Consumer<WhatHappened> callback) {
        this((Node) topic, exclusions, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param topics   topics to subscribe to
     * @param callback action to perform after a batch of changes and on initialization
     */
    public BatchedSubscriber(Topics topics, Consumer<WhatHappened> callback) {
        this(topics, null, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param topics     topics to subscribe to
     * @param exclusions predicate for ignoring a subset topics changes
     * @param callback   action to perform after a batch of changes and on initialization
     */
    public BatchedSubscriber(Topics topics, BiPredicate<WhatHappened, Node> exclusions,
                             Consumer<WhatHappened> callback) {
        this((Node) topics, exclusions, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param node       topic or topics to subscribe to
     * @param exclusions predicate for ignoring a subset topic(s) changes
     * @param callback   action to perform after a batch of changes and on initialization
     */
    private BatchedSubscriber(Node node, BiPredicate<WhatHappened, Node> exclusions,
                              Consumer<WhatHappened> callback) {
        Objects.requireNonNull(node);
        this.node = node;
        this.exclusions = exclusions == null ? BASE_EXCLUSION : exclusions;
        this.callback = callback;
    }

    /**
     * Subscribe to the topic(s).
     */
    public void subscribe() {
        if (node instanceof Topic) {
            ((Topic) node).subscribe(this);
        }
        if (node instanceof Topics) {
            ((Topics) node).subscribe(this);
        }
    }

    /**
     * Unsubscribe from the topic(s).
     */
    public void unsubscribe() {
        node.remove(this);
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
                callback.accept(what);
            }
            return;
        }

        numRequestedChanges.incrementAndGet();
        child.context.runOnPublishQueue(() -> {
            if (numRequestedChanges.decrementAndGet() == 0) {
                callback.accept(what);
            }
        });
    }
}
