/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ExtendWith({GGExtension.class})
public class SubscriptionTrieTest {

    SubscriptionTrie trie;

    @BeforeEach
    void setup() {
        trie = new SubscriptionTrie();
    }

    @ParameterizedTest
    @MethodSource("subscriptionMatch")
    public void GIVEN_subscription_THEN_match(String subscription, List<String> topics) {
        Object cb1 = new Object();
        Object cb2 = new Object();
        trie.add(subscription, cb1);
        trie.add(subscription, cb2);
        for (String topic : topics) {
            assertThat(trie.get(topic), containsInAnyOrder(cb1, cb2));
        }
    }

    static Stream<Arguments> subscriptionMatch() {
        return Stream.of(
                arguments("foo", singletonList("foo")),
                arguments("foo/bar", singletonList("foo/bar")),
                // multilevel wildcard #
                arguments("#", asList("foo", "foo/bar", "foo/bar/baz", "$foo/bar")),
                arguments("foo/#", asList("foo/bar", "foo/bar/baz", "foo/bar/#", "foo/+")),
                // single level wildcard +
                arguments("+", asList("+", "foo", "foo/", "$foo")),
                arguments("foo/+/baz", singletonList("foo/bar/baz")),
                arguments("foo/+/baz/#", asList("foo//baz/bar", "foo/bar/baz/bat", "foo/bar/baz/bat/#"))
        );
    }

    @ParameterizedTest
    @MethodSource("subscriptionNotMatch")
    public void GIVEN_subscription_THEN_do_not_match(String subscription, List<String> topics) {
        Object cb1 = new Object();
        Object cb2 = new Object();
        trie.add(subscription, cb1);
        trie.add(subscription, cb2);

        for (String topic : topics) {
            assertThat(trie.get(topic), not(containsInAnyOrder(cb1, cb2)));
        }
    }

    static Stream<Arguments> subscriptionNotMatch() {
        return Stream.of(
                arguments("foo", asList("fo", "foo/bar", "abc")),
                arguments("foo/bar", asList("foo", "foo/bar/baz", "fo/bar")),
                // multilevel wildcard # does not match parent topic
                arguments("foo/#", asList("foo", "foo/")),
                // single level wildcard +
                arguments("+", asList("/foo", "foo/bar")),
                arguments("foo/+/baz", asList("foo", "foo/baz", "foo/bar/bat/baz"))
        );
    }

    @Test
    public void GIVEN_subscription_WHEN_remove_topic_THEN_no_matches() {
        assertEquals(0, trie.size());
        Object cb1 = new Object();
        Object cb2 = new Object();
        String topic = "foo";
        trie.add(topic, cb1);
        trie.add(topic, cb2);
        assertTrue(trie.containsKey(topic));
        assertThat(trie.get(topic), containsInAnyOrder(cb1, cb2));
        assertEquals(2, trie.size());

        assertThat("remove topic", trie.remove(topic, cb1), is(true));
        assertThat(trie.get(topic), contains(cb2));
        assertEquals(1, trie.size());
        assertThat("remove topic", trie.remove(topic, cb2), is(true));
        assertThat(trie.get(topic), is(empty()));
        assertEquals(0, trie.size());
    }

    @Test
    public void GIVEN_subscription_wildcard_WHEN_remove_topic_THEN_no_matches() {
        assertEquals(0, trie.size());
        Object cb1 = new Object();
        Object cb2 = new Object();
        String topic = "foo/+/bar/#";
        trie.add(topic, cb1);
        trie.add(topic, cb2);
        assertTrue(trie.containsKey(topic));
        assertThat(trie.get(topic), containsInAnyOrder(cb1, cb2));
        assertEquals(2, trie.size());

        assertThat("remove topic", trie.remove(topic, cb1), is(true));
        assertThat(trie.get(topic), contains(cb2));
        assertEquals(1, trie.size());
        assertThat("remove topic", trie.remove(topic, cb2), is(true));
        assertThat(trie.get(topic), is(empty()));
        assertEquals(0, trie.size());
    }
}