/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import java.util.Collections;
import java.util.HashSet;
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
    private final Set<SubscriptionCallback> subscriptionCallbacks;

    /**
     * Construct.
     */
    public SubscriptionTrie() {
        this.subscriptionCallbacks = ConcurrentHashMap.newKeySet();
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
    public boolean remove(String topic, SubscriptionCallback cb) {
        return remove(topic, Collections.singleton(cb));
    }

    /**
     * Remove entry for a set of callbacks.
     *
     * @param topic topic
     * @param cbs   callbacks
     * @return if changed after removal
     */
    public boolean remove(String topic, Set<SubscriptionCallback> cbs) {
        SubscriptionTrie sub = lookup(topic);
        if (sub == null) {
            return false;
        }
        return sub.subscriptionCallbacks.removeAll(cbs);
    }

    /**
     * Size of trie.
     *
     * @return size
     */
    public int size() {
        int size = this.subscriptionCallbacks.size();
        for (SubscriptionTrie child : children.values()) {
            size += child.size();
        }
        return size;
    }

    /**
     * Add a topic callback.
     *
     * @param topic topic
     * @param cb    callback
     * @return true
     */
    public boolean add(String topic, SubscriptionCallback cb) {
        return add(topic, Collections.singleton(cb));
    }

    /**
     * Add a topic and a set of callbacks.
     *
     * @param topic topic
     * @param cbs   callbacks
     */
    public boolean add(String topic, Set<SubscriptionCallback> cbs) {
        SubscriptionTrie current = this;
        for (String topicLevel : topic.split(TOPIC_LEVEL_SEPARATOR)) {
            current = current.children.computeIfAbsent(topicLevel, k -> new SubscriptionTrie());
        }
        return current.subscriptionCallbacks.addAll(cbs);
    }

    private void addMatchingPaths(String topicLevel, Set<SubscriptionCallback> result, Set<SubscriptionTrie> paths) {
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
            result.addAll(childPoundPath.subscriptionCallbacks);
        }
    }

    /**
     * Get callback objects given a topic.
     *
     * @param topic topic
     * @return a set of callback objects
     */
    public Set<SubscriptionCallback> get(String topic) {
        String[] topicLevels = topic.split(TOPIC_LEVEL_SEPARATOR);
        Set<SubscriptionCallback> result = new HashSet<>();
        Set<SubscriptionTrie> paths = new HashSet<>();
        this.addMatchingPaths(topicLevels[0], result, paths);

        for (int level = 1; level < topicLevels.length && !paths.isEmpty(); level++) {
            Set<SubscriptionTrie> newPaths = new HashSet<>();
            for (SubscriptionTrie path : paths) {
                path.addMatchingPaths(topicLevels[level], result, newPaths);
            }
            paths = newPaths;
        }

        for (SubscriptionTrie path : paths) {
            result.addAll(path.subscriptionCallbacks);
        }

        return result;
    }

    /**
     * Return whether a topic contains MQTT style wildcard.
     * If true, + and # must occupy an entire level and # must be the last character.
     *
     * @param topic topic
     * @return whether the topic is wildcard
     */
    public static boolean isWildcard(String topic) {
        String[] topicLevels = topic.split(TOPIC_LEVEL_SEPARATOR);

        int i;
        for (i = 0; i < topicLevels.length; i++) {
            if (SINGLE_LEVEL_WILDCARD.equals(topicLevels[i])) {
                return true;
            }
            if (MULTI_LEVEL_WILDCARD.equals(topicLevels[i]) && i == topicLevels.length - 1) {
                return true;
            }
        }
        return false;

    }

}
