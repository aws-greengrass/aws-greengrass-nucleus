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
import software.amazon.awssdk.aws.greengrass.model.ReceiveMode;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ExtendWith({GGExtension.class})
public class SubscriptionTrieTest {

    SubscriptionTrie<SubscriptionCallback> trie;

    @BeforeEach
    void setup() {
        trie = new SubscriptionTrie<>();
    }

    @ParameterizedTest
    @MethodSource("subscriptionMatch")
    public void GIVEN_subscription_THEN_match(String subscription, List<String> topics) {
        SubscriptionCallback cb1 = generateSubscriptionCallback();
        SubscriptionCallback cb2 = generateSubscriptionCallback();
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
        SubscriptionCallback cb1 = generateSubscriptionCallback();
        SubscriptionCallback cb2 = generateSubscriptionCallback();
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
        SubscriptionCallback cb1 = generateSubscriptionCallback();
        SubscriptionCallback cb2 = generateSubscriptionCallback();
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
        SubscriptionCallback cb1 = generateSubscriptionCallback();
        SubscriptionCallback cb2 = generateSubscriptionCallback();
        SubscriptionCallback cb3 = generateSubscriptionCallback();
        String topic = "foo/+/bar/#";
        trie.add(topic, cb1);
        trie.add(topic, cb2);
        trie.add("foo/#", cb3);
        assertTrue(trie.containsKey(topic));
        assertThat(trie.get(topic), containsInAnyOrder(cb1, cb2, cb3));
        assertThat(trie.get("foo/#"), containsInAnyOrder(cb3));
        assertEquals(3, trie.size());

        assertThat("remove topic", trie.remove("foo/#", cb3), is(true));
        assertThat(trie.get("foo/#"), is(empty()));
        assertEquals(2, trie.size());
        assertThat("remove topic", trie.remove(topic, cb1), is(true));
        assertThat(trie.get(topic), contains(cb2));
        assertEquals(1, trie.size());
        assertThat("remove topic", trie.remove(topic, cb2), is(true));
        assertThat(trie.get(topic), is(empty()));
        assertEquals(0, trie.size());
    }

    @Test
    public void GIVEN_subscriptions_with_wildcards_WHEN_remove_topics_THEN_clean_up_trie() {
        assertEquals(0, trie.size());
        SubscriptionCallback cb1 = generateSubscriptionCallback();
        SubscriptionCallback cb2 = generateSubscriptionCallback();
        SubscriptionCallback cb3 = generateSubscriptionCallback();
        String topic = "foo/+/bar/#";
        trie.add("bar", cb1);
        trie.add(topic, cb1);
        trie.add(topic, cb2);
        // Topic is not registered with the callback, mark it as removed when requested to remove
        assertThat("remove topic", trie.remove(topic, cb3), is(false));
        assertThat(trie.get(topic), containsInAnyOrder(cb1, cb2));

        trie.add("foo/#", cb3);
        trie.add("foo/+", cb2);

        assertThat(trie.get(topic), containsInAnyOrder(cb1, cb2, cb3));
        assertEquals(5, trie.size());

        assertThat("remove topic", trie.remove("foo/+", cb2), is(true));
        assertEquals(4, trie.size());
        assertThat(trie.get("foo/+"), containsInAnyOrder(cb3)); // foo/+ still matches with foo/#
        assertThat(trie.get(topic), containsInAnyOrder(cb1, cb2, cb3)); // foo/+/bar/# still exists

        assertThat("remove topic", trie.remove("foo/#", cb3), is(true));
        assertFalse(trie.containsKey("foo/#"));
        assertThat(trie.get("foo/#"), is(empty()));
        assertThat(trie.get("foo/+"), is(empty())); // foo/+ doesn't match with any existing topic
        assertThat(trie.get(topic), containsInAnyOrder(cb1, cb2)); // foo/+/bar/# still exists
        assertEquals(3, trie.size());

        assertThat("remove topic", trie.remove(topic, cb1), is(true));
        assertThat(trie.get(topic), contains(cb2));
        assertEquals(2, trie.size());
        assertTrue(trie.containsKey("foo/+"));
        assertTrue(trie.containsKey("foo/+/bar/#"));

        assertThat("remove topic", trie.remove(topic, cb2), is(true));
        assertThat(trie.get(topic), is(empty()));
        assertEquals(1, trie.size());
        assertFalse(trie.containsKey("foo/+"));
        assertFalse(trie.containsKey("foo/+/bar/#"));
    }

    @Test
    void GIVEN_topics_WHEN_isWildcard_THEN_returns_whether_it_uses_wildcard() {
        assertTrue(SubscriptionTrie.isWildcard("+"));
        assertTrue(SubscriptionTrie.isWildcard("#"));
        assertTrue(SubscriptionTrie.isWildcard("/+/"));
        assertTrue(SubscriptionTrie.isWildcard("/#"));
        assertTrue(SubscriptionTrie.isWildcard("foo/+"));
        assertTrue(SubscriptionTrie.isWildcard("foo/ba+/#"));
        assertTrue(SubscriptionTrie.isWildcard("foo/+/bar/#"));
        assertTrue(SubscriptionTrie.isWildcard("$aws/things/+/shadow/#"));

        assertFalse(SubscriptionTrie.isWildcard("/"));
        assertFalse(SubscriptionTrie.isWildcard("foo"));
        assertFalse(SubscriptionTrie.isWildcard("#/foo"));
        assertFalse(SubscriptionTrie.isWildcard("foo/+bar"));
        assertFalse(SubscriptionTrie.isWildcard("foo/+#"));
    }

    private SubscriptionCallback generateSubscriptionCallback() {
        return new SubscriptionCallback("TEST_COMPONENT", ReceiveMode.RECEIVE_ALL_MESSAGES, new Object());
    }
}