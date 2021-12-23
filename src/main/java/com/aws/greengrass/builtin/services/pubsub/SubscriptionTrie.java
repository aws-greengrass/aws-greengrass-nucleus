/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trie to manage subscriptions.
 */
public class SubscriptionTrie {
    private static final String TOPIC_LEVEL_SEPARATOR = "/";
    private static final String SINGLE_LEVEL_WILDCARD = "+";
    private static final String MULTI_LEVEL_WILDCARD = "#";

    private final Map<String, SubscriptionTrie> children = new ConcurrentHashMap<>();
    @SuppressWarnings("PMD.UnusedPrivateField")
    @SuppressFBWarnings("URF_UNREAD_FIELD")
    private final String value;
    private final Set<Object> callbacks;

    /**
     * Construct.
     */
    public SubscriptionTrie() {
        this("");
    }

    private SubscriptionTrie(String value) {
        this.value = value;
        this.callbacks = ConcurrentHashMap.newKeySet();
    }

    private SubscriptionTrie lookup(String topic) {
        SubscriptionTrie current = this;
        for (String topicLevel : topic.split(TOPIC_LEVEL_SEPARATOR)) {
            current = current.children.get(topicLevel);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Check if trie contains a topic.
     *
     * @param topic topic
     * @return if trie contains topic
     */
    public boolean containsKey(String topic) {
        return lookup(topic) != null;
    }

    /**
     * Remove entry for one callback.
     *
     * @param topic topic
     * @param cb    callback
     * @return if changed after removal
     */
    public boolean remove(String topic, Object cb) {
        return remove(topic, Collections.singleton(cb));
    }

    /**
     * Remove entry for a set of callbacks.
     *
     * @param topic topic
     * @param cbs   callbacks
     * @return if changed after removal
     */
    public boolean remove(String topic, Set<Object> cbs) {
        SubscriptionTrie sub = lookup(topic);
        if (sub == null) {
            return false;
        }
        return sub.callbacks.removeAll(cbs);
    }

    /**
     * Size of trie.
     *
     * @return size
     */
    public int size() {
        int[] size = {this.callbacks.size()};
        children.forEach((s, t) -> {
            size[0] += t.size();
        });
        return size[0];
    }

    /**
     * Add a topic callback.
     *
     * @param topic topic
     * @param cb    callback
     * @return true
     */
    public boolean add(String topic, Object cb) {
        ConcurrentHashMap.KeySetView<Object, Boolean> cbs = ConcurrentHashMap.newKeySet();
        cbs.add(cb);
        put(topic, cbs);
        return true;
    }

    /**
     * Add a topic and a set of callbacks.
     *
     * @param topic topic
     * @param cbs   callbacks
     */
    public void put(String topic, Set<Object> cbs) {
        SubscriptionTrie[] current = {this};
        for (String topicLevel : topic.split(TOPIC_LEVEL_SEPARATOR)) {
            current[0] = current[0].children.computeIfAbsent(topicLevel, SubscriptionTrie::new);
        }
        current[0].callbacks.addAll(cbs);
    }

    private Set<SubscriptionTrie> getMatchingPaths(String topicLevel, Set<Object> result) {
        Set<SubscriptionTrie> paths = new LinkedHashSet<>();

        SubscriptionTrie childPath = this.children.get(topicLevel);
        if (childPath != null) {
            paths.add(childPath);
        }

        SubscriptionTrie childPlusPath = this.children.get(SINGLE_LEVEL_WILDCARD);
        if (childPlusPath != null) {
            paths.add(childPlusPath);
        }

        SubscriptionTrie childPoundPath = this.children.get(MULTI_LEVEL_WILDCARD);
        if (childPoundPath != null) {
            paths.add(childPoundPath);
            result.addAll(childPoundPath.callbacks);
        }

        return paths;
    }

    /**
     * Get callback objects given a topic.
     *
     * @param topic topic
     * @return a set of callback objects
     */
    public Set<Object> get(String topic) {
        String[] topicLevels = topic.split(TOPIC_LEVEL_SEPARATOR);
        Set<Object> result = new LinkedHashSet<>();
        Set<SubscriptionTrie> paths = this.getMatchingPaths(topicLevels[0], result);

        for (int iter = 1; iter < topicLevels.length && !paths.isEmpty(); iter++) {
            Set<SubscriptionTrie> newPaths = new LinkedHashSet<>();
            for (SubscriptionTrie path : paths) {
                Set<SubscriptionTrie> childrenPath = path.getMatchingPaths(topicLevels[iter], result);
                newPaths.addAll(childrenPath);
            }
            paths = newPaths;
        }

        for (SubscriptionTrie path : paths) {
            result.addAll(path.callbacks);
        }

        return result;
    }

}
