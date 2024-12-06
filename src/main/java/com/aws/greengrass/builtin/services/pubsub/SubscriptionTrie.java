/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.util.Utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trie to manage subscriptions.
 */
public class SubscriptionTrie<K> {
    private static final String TOPIC_LEVEL_SEPARATOR = "/";
    private static final String SINGLE_LEVEL_WILDCARD = "+";
    private static final String MULTI_LEVEL_WILDCARD = "#";

    private final Map<String, SubscriptionTrie<K>> children = new ConcurrentHashMap<>();
    private final Set<K> subscriptionCallbacks;

    /**
     * Construct.
     */
    public SubscriptionTrie() {
        this.subscriptionCallbacks = ConcurrentHashMap.newKeySet();
    }

    private SubscriptionTrie<K> lookup(String topic) {
        SubscriptionTrie<K> current = this;
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
    public boolean remove(String topic, K cb) {
        return remove(topic, Collections.singleton(cb));
    }

    /**
     * Remove entry for a set of callbacks.
     *
     * @param topic topic
     * @param cbs   callbacks
     * @return if changed after removal
     */
    public boolean remove(String topic, Set<K> cbs) {
        if (Utils.isEmpty(topic) || Utils.isEmpty(cbs)) {
            return false;
        }

        AtomicBoolean isSubscriptionRemoved = new AtomicBoolean(false);
        removeRecursively(topic.split(TOPIC_LEVEL_SEPARATOR), this, cbs, 0, isSubscriptionRemoved);
        return isSubscriptionRemoved.get();
    }

    private boolean canRemove(SubscriptionTrie<K> node) {
        // returns false if the topic level is prefix of another topic or has callbacks registered
        return node.children.isEmpty() && node.subscriptionCallbacks.isEmpty();
    }

    /*
    This method removes the requested callback from a topic and prunes the subscription trie recursively by
    1. navigating to the last node of the requested topic
    1.a. at this node, the method persists the result of the requested callback removal for the penultimate result
    1.b. returns true if requested callback was removed and if current topic node can be pruned

    2. the previous topic node receives the result from 1.b.
    2.a. if true, remove child topic node and return if current node can be pruned
    2.b. if false, simply do nothing and return false implying current node cannot be pruned
     */
    private boolean removeRecursively(String[] topicNodes, SubscriptionTrie<K> topicNode, Set<K> cbs, int index,
                                      AtomicBoolean subscriptionRemoved) {
        if (index == topicNodes.length) {
            subscriptionRemoved.set(topicNode.subscriptionCallbacks.removeAll(cbs));
            return subscriptionRemoved.get() && canRemove(topicNode);
        }

        if (removeRecursively(topicNodes, topicNode.children.get(topicNodes[index]), cbs, index + 1,
                subscriptionRemoved)) {
            topicNode.children.remove(topicNodes[index]);
            return canRemove(topicNode);
        }
        return false;
    }

    /**
     * Size of trie.
     *
     * @return size
     */
    public int size() {
        int size = this.subscriptionCallbacks.size();
        for (SubscriptionTrie<K> child : children.values()) {
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
    public boolean add(String topic, K cb) {
        return add(topic, Collections.singleton(cb));
    }

    /**
     * Add a topic and a set of callbacks.
     *
     * @param topic topic
     * @param cbs   callbacks
     */
    public boolean add(String topic, Set<K> cbs) {
        SubscriptionTrie<K> current = this;
        for (String topicLevel : topic.split(TOPIC_LEVEL_SEPARATOR)) {
            current = current.children.computeIfAbsent(topicLevel, k -> new SubscriptionTrie<>());
        }
        return current.subscriptionCallbacks.addAll(cbs);
    }

    private void addMatchingPaths(String topicLevel, Set<K> result, Set<SubscriptionTrie<K>> paths) {
        SubscriptionTrie<K> childPath = this.children.get(topicLevel);
        if (childPath != null) {
            paths.add(childPath);
        }

        SubscriptionTrie<K> childPlusPath = this.children.get(SINGLE_LEVEL_WILDCARD);
        if (childPlusPath != null) {
            paths.add(childPlusPath);
        }

        SubscriptionTrie<K> childPoundPath = this.children.get(MULTI_LEVEL_WILDCARD);
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
    public Set<K> get(String topic) {
        String[] topicLevels = topic.split(TOPIC_LEVEL_SEPARATOR);
        Set<K> result = new HashSet<>();
        Set<SubscriptionTrie<K>> paths = new HashSet<>();
        this.addMatchingPaths(topicLevels[0], result, paths);

        for (int level = 1; level < topicLevels.length && !paths.isEmpty(); level++) {
            Set<SubscriptionTrie<K>> newPaths = new HashSet<>();
            for (SubscriptionTrie<K> path : paths) {
                path.addMatchingPaths(topicLevels[level], result, newPaths);
            }
            paths = newPaths;
        }

        for (SubscriptionTrie<K> path : paths) {
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

