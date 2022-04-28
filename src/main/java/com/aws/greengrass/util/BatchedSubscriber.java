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
import lombok.NonNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * {@link BatchedSubscriber} is a subscriber that fires once for a <i>batch</i> of changes
 * (and on subscription initialization).
 *
 * <br><br><p>A <i>batch</i> is defined as all the elements in a {@link Topic} or {@link Topics}' publish queue,
 * with the last <i>batch</i> element being the most recent topic change.
 *
 * <br><br><p>By default, commonly ignored changes, like {@link WhatHappened#timestampUpdated} and
 * {@link WhatHappened#interiorAdded}, will NOT be added to a <i>batch</i>
 * (see {@link BatchedSubscriber#BASE_EXCLUSION}).
 *
 * <br><br><p>To be precise, a {@link BatchedSubscriber} will trigger its {@link BatchedSubscriber#callback}
 * after the following events:
 * <ul>
 *     <li>when {@link WhatHappened#initialized} is fired on initial subscription</li>
 *     <li>when the last <i>batch</i> element is popped from the topic's publish queue</li>
 * </ul>
 */
public final class BatchedSubscriber implements ChildChanged, Subscriber {

    public static final BiPredicate<WhatHappened, Node> BASE_EXCLUSION = (what, child) ->
            what == WhatHappened.timestampUpdated || what == WhatHappened.interiorAdded;

    private final AtomicInteger numRequestedChanges = new AtomicInteger();

    private final Node node;
    private final BiPredicate<WhatHappened, Node> exclusions;
    private final Consumer<WhatHappened> callback;

    /**
     * Constructs a new BatchedSubscriber.
     *
     * <p>Defaults to using {@link BatchedSubscriber#BASE_EXCLUSION} for excluding changes from a <i>batch</i>.
     *
     * @param topic    topic to subscribe to
     * @param callback action to perform after a <i>batch</i> of changes and on initialization
     */
    public BatchedSubscriber(Topic topic, Consumer<WhatHappened> callback) {
        this(topic, BASE_EXCLUSION, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param topic      topic to subscribe to
     * @param exclusions predicate for ignoring a subset topic changes
     * @param callback   action to perform after a <i>batch</i> of changes and on initialization
     */
    public BatchedSubscriber(Topic topic,
                             BiPredicate<WhatHappened, Node> exclusions,
                             Consumer<WhatHappened> callback) {
        this((Node) topic, exclusions, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * <p>Defaults to using {@link BatchedSubscriber#BASE_EXCLUSION} for excluding changes from a <i>batch</i>.
     *
     * @param topics   topics to subscribe to
     * @param callback action to perform after a <i>batch</i> of changes and on initialization
     */
    public BatchedSubscriber(Topics topics, Consumer<WhatHappened> callback) {
        this(topics, BASE_EXCLUSION, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param topics     topics to subscribe to
     * @param exclusions predicate for ignoring a subset topics changes
     * @param callback   action to perform after a <i>batch</i> of changes and on initialization
     */
    public BatchedSubscriber(Topics topics,
                             BiPredicate<WhatHappened, Node> exclusions,
                             Consumer<WhatHappened> callback) {
        this((Node) topics, exclusions, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param exclusions predicate for ignoring a subset topic(s) changes
     * @param callback   action to perform after a <i>batch</i> of changes and on initialization
     */
    public BatchedSubscriber(BiPredicate<WhatHappened, Node> exclusions,
                             Consumer<WhatHappened> callback) {
        this((Node) null, exclusions, callback);
    }

    /**
     * Constructs a new BatchedSubscriber.
     *
     * @param node       topic or topics to subscribe to
     * @param exclusions predicate for ignoring a subset topic(s) changes
     * @param callback   action to perform after a <i>batch</i> of changes and on initialization
     */
    private BatchedSubscriber(Node node,
                              BiPredicate<WhatHappened, Node> exclusions,
                              @NonNull Consumer<WhatHappened> callback) {
        this.node = node;
        this.exclusions = exclusions;
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
        if (node != null) {
            node.remove(this);
        }
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
        if (exclusions != null && exclusions.test(what, child)) {
            return;
        }

        if (what == WhatHappened.initialized) {
            callback.accept(what);
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
