/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.util.Coerce.toInt;
import static com.fasterxml.jackson.jr.ob.JSON.Feature.PRETTY_PRINT_OUTPUT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings({"PMD.DetachedTestCase", "PMD.UnusedLocalVariable"})
@ExtendWith(GGExtension.class)
class ConfigurationTest {

    private Configuration config;

    @BeforeEach()
    void beforeEach() {
        config = new Configuration(new Context());
    }

    @AfterEach
    void afterEach() throws IOException {
        config.context.close();
    }

    @Test
    void GIVEN_empty_config_WHEN_single_topic_created_and_updated_THEN_update_if_timestamp_is_valid() {
        config.lookup("v").addValidator((n, o) -> {
            if (o != null) {
                assertEquals(toInt(n), toInt(o) + 1);
            }
            return n;
        });
        config.lookup("v").withNewerValue(0, 42);
        config.lookup("v").withNewerValue(10, 43);
        config.lookup("v").withNewerValue(3, -1);
        config.lookup("v").withNewerValue(20, 44);
        assertEquals(44, config.lookup("V").getOnce());
        assertEquals("v:44", config.lookup("V").toString());
    }

    @Test
    void GIVEN_empty_config_WHEN_nested_topic_created_and_updated_THEN_update_if_timestamp_is_valid() {
        config.lookup("x", "y").addValidator((n, o) -> {
            if (o != null) {
                assertEquals(toInt(n), toInt(o) + 1);
            }
            return n;
        });
        config.lookup("x", "y").withNewerValue(0, 42);
        config.lookup("x", "y").withNewerValue(10, 43);
        config.lookup("x", "y").withNewerValue(3, -1);
        config.lookup("x", "y").withNewerValue(20, 44);
        assertEquals(44, toInt(config.lookup("x", "Y")));
        assertEquals("x.y:44", config.lookup("x", "Y").toString());
    }

    @Test
    void GIVEN_empty_config_WHEN_nested_topic_with_path_created_and_updated_THEN_update_if_timestamp_is_valid() {
        config.lookup("x", "z").addValidator((n, o) -> {
            if (o != null) {
                assertEquals(toInt(n), toInt(o) + 1);
            }
            return n;
        });
        config.lookup("x", "z").withNewerValue(0, 42);
        config.lookup("x", "z").withNewerValue(10, 43);
        config.lookup("x", "z").withNewerValue(3, -1);
        config.lookup("x", "z").withNewerValue(20, 44);
        assertEquals(44, toInt(config.lookup("x", "z")));
        assertEquals("x.z:44", config.lookup("x", "z").toString());
    }

    @Test
    void GIVEN_non_empty_config_WHEN_topics_created_and_updated_THEN_update_if_timestamp_is_valid() throws Exception {
        GIVEN_empty_config_WHEN_single_topic_created_and_updated_THEN_update_if_timestamp_is_valid();
        GIVEN_empty_config_WHEN_nested_topic_created_and_updated_THEN_update_if_timestamp_is_valid();
        GIVEN_empty_config_WHEN_nested_topic_with_path_created_and_updated_THEN_update_if_timestamp_is_valid();
        config.lookup("x", "a").withNewerValue(20, "hello");
        config.lookup("x", "b").withNewerValue(20, true);
        config.lookup("x", "c").withNewerValue(20, Math.PI);
        config.lookup("x", "d").withNewerValue(20, System.currentTimeMillis());
        Path p = Paths.get("/tmp/c.log");
        ConfigurationWriter.dump(config, p);
        assertEquals(config.getRoot(), config.getRoot());
        Configuration c2 = ConfigurationReader.createFromTLog(config.context, p);
        assertEquals(44, c2.lookup("x", "z").getOnce());
        assertEquals(config, c2);
        Topic nv = config.lookup("number");
    }

    @Test
    void GIVEN_yaml_file_to_merge_WHEN_merge_map_THEN_merge() throws Throwable {
        try (InputStream inputStream = getClass().getResourceAsStream("test.yaml")) {
            assertNotNull(inputStream);
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

            Topics platforms = config.findTopics("platforms");

            Topic testValue = config.lookup("number");
            testValue.addValidator((nv, ov) -> {
                int v = toInt(nv);
                if (v < 0) {
                    v = 0;
                }
                if (v > 100) {
                    v = 100;
                }
                return v;
            });
            testValue.withValue("53");
            assertEquals(53, testValue.getOnce());
            testValue.withValue(-10);
            assertEquals(0, testValue.getOnce());
            StringWriter sw = new StringWriter();
            JSON.std.with(PRETTY_PRINT_OUTPUT).with(new YAMLFactory()).write(config.toPOJO(), sw);
            String tc = sw.toString();
            assertThat(tc, StringContains.containsString("\"{platform.invoke} {name}\""));
            assertThat(tc, StringContains.containsString("dependencies:\n    - \"greenlake\""));
        }
    }

    @Test
    void GIVEN_empty_configuration_WHEN_topic_lookup_THEN_topic_created() {
        assertNull(config.find("root", "leaf"));
        Topic createdTopic = config.lookup("root", "leaf").dflt("defaultValue");
        assertEquals(createdTopic, config.find("root", "leaf"));
        assertEquals("defaultValue", createdTopic.getOnce());
    }

    @Test
    void GIVEN_empty_configuration_WHEN_topics_lookup_THEN_topics_created() {
        assertNull(config.findTopics("root", "child"));
        Topics createdTopics = config.lookupTopics("root", "child");
        assertEquals(createdTopics, config.findTopics("root", "child"));
    }

    @Test
    void GIVEN_configuration_WHEN_findNode_called_THEN_correct_node_returned() {
        assertNull(config.findNode("root", "container", "leaf"));
        Topic createdTopic = config.lookup("root", "container", "leaf").dflt("defaultValue");
        assertEquals("defaultValue", createdTopic.getOnce());
        assertEquals(createdTopic, config.findNode("root", "container", "leaf"));

        Topics containerNode = config.findTopics("root", "container");
        assertEquals("defaultValue", containerNode.findLeafChild("leaf").getOnce());
        assertEquals(containerNode, config.findNode("root", "container"));
    }

    @Test
    void GIVEN_config_with_subscribers_WHEN_topic_updated_THEN_subscribers_notified_with_changed_node()
            throws Exception {
        Topic installTopic = config.lookup(SERVICES_NAMESPACE_TOPIC, "serviceA", "lifecycle", "install").dflt("default");
        CountDownLatch childChangedCorrectly = new CountDownLatch(1);
        config.findTopics(SERVICES_NAMESPACE_TOPIC, "serviceA").subscribe((what, child) -> {
            if (what.equals(WhatHappened.childChanged)
                    && child.childOf("install")
                    && ((Topic) child).getOnce().equals("Install")) {
                childChangedCorrectly.countDown();
            }
        });
        installTopic.withValue("Install");
        assertTrue(childChangedCorrectly.await(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void GIVEN_config_with_subscribers_WHEN_topic_removed_THEN_subscribers_notified() throws Exception {
        Topic testTopic = config.lookup("a", "b", "c");

        AtomicInteger numCalled = new AtomicInteger(0);
        Pair<CompletableFuture<Void>, BiConsumer<WhatHappened, Topic>> childRemoved =
                TestUtils.asyncAssertOnBiConsumer((what, t) -> {
                    if (numCalled.get() == 0) {
                        assertEquals(WhatHappened.initialized, what);
                    } else if (numCalled.get() == 1) {
                        assertEquals(WhatHappened.removed, what);
                    }
                    numCalled.incrementAndGet();
                }, 2);
        testTopic.subscribe((w, t) -> childRemoved.getRight().accept(w, t));

        AtomicInteger parentNumCalled = new AtomicInteger(0);
        Pair<CompletableFuture<Void>, BiConsumer<WhatHappened, Node>> parentNotified =
                TestUtils.asyncAssertOnBiConsumer((what, t) -> {
                    if (parentNumCalled.get() == 0) {
                        assertEquals(WhatHappened.initialized, what);
                        parentNumCalled.incrementAndGet();
                    } else if (parentNumCalled.get() == 1) {
                        assertEquals(WhatHappened.childRemoved, what);
                        assertEquals(testTopic, t);
                    }
                }, 2);
        config.findTopics("a", "b").subscribe((w, n) -> parentNotified.getRight().accept(w, n));

        testTopic.remove();

        childRemoved.getLeft().get(100, TimeUnit.MILLISECONDS);
        parentNotified.getLeft().get(100, TimeUnit.MILLISECONDS);
        assertNull(config.find("a", "b", "c"));
        assertFalse(config.findTopics("a", "b").children.containsKey("c"));
    }

    @Test
    void GIVEN_config_with_subscribers_WHEN_topics_removed_THEN_children_notified() {
        config.lookup("a", "b", "c");
        AtomicInteger[] childNotified = new AtomicInteger[3];

        childNotified[0] = new AtomicInteger(0);
        config.lookupTopics("a").subscribe((what, t) -> {
            if (what.equals(WhatHappened.removed)) {
                childNotified[0].incrementAndGet();
            }
        });

        childNotified[1] = new AtomicInteger(0);
        config.lookupTopics("a", "b").subscribe((what, t) -> {
            if (what.equals(WhatHappened.removed)) {
                childNotified[1].incrementAndGet();
            }
        });

        childNotified[2] = new AtomicInteger(0);
        config.lookup("a", "b", "c").subscribe((what, t) -> {
            if (what.equals(WhatHappened.removed)) {
                childNotified[2].incrementAndGet();
            }
        });

        config.lookupTopics("a").remove();
        config.context.runOnPublishQueueAndWait(() -> {});

        assertEquals(1, childNotified[0].get());
        assertNull(config.findTopics("a"));
        assertFalse(config.root.children.containsKey("a"));

        assertEquals(1, childNotified[1].get());
        assertEquals(1, childNotified[2].get());
    }

    @Test
    void GIVEN_config_to_merge_WHEN_resolved_platform_is_not_a_map_THEN_reject() {
        Map<String, Object> toMerge = new HashMap<String, Object>() {{
            put("ubuntu", "This is not a map");
        }};
        assertThrows(IllegalArgumentException.class, () -> config.mergeMap(1, toMerge));
    }

    @Test
    void GIVEN_unsupported_merge_file_type_WHEN_merge_map_THEN_discard() throws Exception {
        config.readMerge(getClass().getResource("test.txt").toURI().toURL(), true);
        assertTrue(config.isEmpty());
    }

    @Test
    void GIVEN_json_file_to_merge_WHEN_merge_map_THEN_merge() throws Exception {
        assertTrue(config.isEmpty());
        assertEquals(0, config.size());
        config.readMerge(getClass().getResource("test.json").toURI().toURL(), true);
        assertEquals("echo main service installed",
                config.find(SERVICES_NAMESPACE_TOPIC, "main", "lifecycle", "install").getOnce());
        assertFalse(config.isEmpty());
        assertEquals(1, config.size());
    }

    @Test
    void GIVEN_tlog_file_to_merge_WHEN_merge_map_THEN_merge() throws Exception {
        config.readMerge(getClass().getResource("test.tlog").toURI().toURL(), true);
        assertEquals("echo All installed",
                config.find(SERVICES_NAMESPACE_TOPIC, "main", "lifecycle", "install").getOnce());
    }

    @Test
    void GIVEN_config_file_path_WHEN_read_from_path_THEN_merge() throws Exception {
        config.read(getClass().getResource("test.json").toURI().toURL(), true);
        assertEquals("echo main service installed",
                config.find(SERVICES_NAMESPACE_TOPIC, "main", "lifecycle", "install").getOnce());
    }

    @Test
    void GIVEN_config_to_merge_WHEN_read_with_current_timestamp_THEN_merge() throws Exception {
        config.read(getClass().getResource("test.json").toURI().toURL(), false);
        assertEquals("echo main service installed",
                config.find(SERVICES_NAMESPACE_TOPIC, "main", "lifecycle", "install").getOnce());
    }

    @Test
    void GIVEN_topics_WHEN_call_replace_map_THEN_content_replaced_and_subscribers_invoked() throws Exception {
        // GIVEN
        // set up initial config and listeners
        String initConfig = "---\n"
                + "foo:\n"
                + "  nodeToBeRemoved:\n"
                + "    key1: val1\n"
                + "  leafToBeUpdated: val2\n"
                + "  nodeUnchanged: unchanged\n"
                + "  leafToBeRemoved: dummy";

        String updateConfig = "---\n"
                + "foo:\n"
                + "  nodeAdded: val1\n"
                + "  nodeUnchanged: unchanged\n"
                + "  leafToBeUpdated: updatedValue";

        Map<String, Object> initConfigMap;
        try (InputStream inputStream = new ByteArrayInputStream(initConfig.getBytes())) {
            initConfigMap = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }
        config.mergeMap(System.currentTimeMillis(), initConfigMap);
        config.context.runOnPublishQueueAndWait(() -> {});

        AtomicInteger containerNodeRemoved = new AtomicInteger(0);
        config.findTopics("foo", "nodeToBeRemoved").subscribe((what, c) -> {
            if (WhatHappened.removed == what) {
                containerNodeRemoved.incrementAndGet();
            }
        });

        AtomicInteger leafNodeRemoved = new AtomicInteger(0);
        config.find("foo", "leafToBeRemoved").subscribe((what, c) -> {
            if (WhatHappened.removed == what) {
                leafNodeRemoved.incrementAndGet();
            }
        });

        AtomicBoolean nodeUnchangedNotified = new AtomicBoolean(false);
        config.find("foo", "nodeUnchanged").subscribe((what, c) -> {
            if (WhatHappened.initialized != what) {
                nodeUnchangedNotified.set(true);
            }
        });

        AtomicInteger leafNodeUpdated = new AtomicInteger(0);
        config.find("foo", "leafToBeUpdated").subscribe((what, c) -> {
            if (WhatHappened.changed == what && c.getOnce().equals("updatedValue")) {
                leafNodeUpdated.incrementAndGet();
            }
        });

        // WHEN
        Map<String, Object> updateConfigMap;
        try (InputStream inputStream = new ByteArrayInputStream(updateConfig.getBytes())) {
            updateConfigMap = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }
        config.updateMap(System.currentTimeMillis(), updateConfigMap,
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE));

        // THEN
        assertEquals(updateConfigMap, config.toPOJO());

        // block until all subscribers are notified
        config.context.runOnPublishQueueAndWait(() -> {});

        assertEquals(1, leafNodeRemoved.get());
        assertEquals(1, containerNodeRemoved.get());
        assertEquals(1, leafNodeUpdated.get());
        assertFalse(nodeUnchangedNotified.get());
    }

    @Test
    void GIVEN_config_update_WHEN_root_replace_and_child_merge_THEN_expect_merge_correct() throws Exception {
        // GIVEN
        // set up initial config and listeners
        String initConfig = "---\n"
                + "foo:\n"
                + "  nodeToBeRemoved:\n"
                + "    key1: val1\n"
                + "  nodeToBeMerged:\n"
                + "    key1: val1\n"
                + "  leafToBeUpdated: val2\n"
                + "  nodeUnchanged: unchanged\n"
                + "  leafToBeRemoved: dummy";

        String updateConfig = "---\n"
                + "foo:\n"
                + "  nodeAdded: val1\n"
                + "  nodeToBeMerged:\n"
                + "    key2: val2\n"
                + "  nodeUnchanged: unchanged\n"
                + "  leafToBeUpdated: updatedValue";

        Map<String, Object> initConfigMap;
        try (InputStream inputStream = new ByteArrayInputStream(initConfig.getBytes())) {
            initConfigMap = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }
        config.mergeMap(System.currentTimeMillis(), initConfigMap);
        config.context.runOnPublishQueueAndWait(() -> {});

        AtomicInteger nodeMerged = new AtomicInteger(0);
        config.findTopics("foo", "nodeToBeMerged").subscribe((what, c) -> {
            if (WhatHappened.childChanged == what && c.getName().equals("key2")) {
                nodeMerged.incrementAndGet();
            }
        });

        // WHEN
        Map<String, Object> updateConfigMap;
        try (InputStream inputStream = new ByteArrayInputStream(updateConfig.getBytes())) {
            updateConfigMap = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }

        UpdateBehaviorTree updateBehavior = new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE,
            createNewMap("foo", new UpdateBehaviorTree(
                    UpdateBehaviorTree.UpdateBehavior.REPLACE,
                    createNewMap("nodeToBeMerged", new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE))
            ))
        );
        config.updateMap(System.currentTimeMillis(), updateConfigMap, updateBehavior);

        Map<String, Object> expectedConfig = new HashMap<>(updateConfigMap);
        ((Map) ((Map)expectedConfig.get("foo")).get("nodeToBeMerged")).put("key1", "val1");

        // THEN
        assertEquals(expectedConfig, config.toPOJO());

        // block until all subscribers are notified
        config.context.runOnPublishQueueAndWait(() -> {});
        assertEquals(1, nodeMerged.get());
    }

    @Test
    void GIVEN_config_update_WHEN_root_merge_and_child_replace_THEN_expect_merge_correct() throws Exception {
        // GIVEN
        // set up initial config and listeners
        String initConfig = "---\n"
                + "foo:\n"
                + "  nodeToBeReplaced:\n"
                + "    key1: val1\n"
                + "  nodeToBeMerged:\n"
                + "    key1: val1\n"
                + "  nodeUnchanged:\n"
                + "    key1: val1\n";

        String updateConfig = "---\n"
                + "foo:\n"
                + "  nodeToBeReplaced:\n"
                + "    key2: val2\n"
                + "  nodeToBeMerged:\n"
                + "    key2: val2\n"
                + "  nodeUnchanged:\n"
                + "    key1: val1\n";

        String expectedResult = "---\n"
                + "foo:\n"
                + "  nodeToBeReplaced:\n"
                + "    key2: val2\n"
                + "  nodeToBeMerged:\n"
                + "    key1: val1\n"
                + "    key2: val2\n"
                + "  nodeUnchanged:\n"
                + "    key1: val1\n";

        Map<String, Object> initConfigMap;
        try (InputStream inputStream = new ByteArrayInputStream(initConfig.getBytes())) {
            initConfigMap = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }
        config.mergeMap(System.currentTimeMillis(), initConfigMap);
        config.context.runOnPublishQueueAndWait(() -> {});

        AtomicInteger nodeMerged = new AtomicInteger(0);
        config.findTopics("foo", "nodeToBeMerged").subscribe((what, c) -> {
            if (WhatHappened.childChanged == what && c.getName().equals("key2")) {
                nodeMerged.incrementAndGet();
            }
        });


        AtomicInteger nodeUnchangedCount = new AtomicInteger(0);
        config.findTopics("foo", "nodeUnchanged").subscribe((what, c) -> {
            if (WhatHappened.initialized != what) {
                nodeUnchangedCount.incrementAndGet();
            }
        });

        // WHEN
        Map<String, Object> updateConfigMap;
        try (InputStream inputStream = new ByteArrayInputStream(updateConfig.getBytes())) {
            updateConfigMap = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }

        UpdateBehaviorTree updateBehavior = new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE,
            createNewMap("*", new UpdateBehaviorTree(
                    UpdateBehaviorTree.UpdateBehavior.MERGE,
                    createNewMap("nodeToBeReplaced",
                            new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE))
            ))
        );

        config.updateMap(System.currentTimeMillis(), updateConfigMap, updateBehavior);

        // THEN
        Map<String, Object> expectedConfig;
        try (InputStream inputStream = new ByteArrayInputStream(expectedResult.getBytes())) {
            expectedConfig = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }
        assertEquals(expectedConfig, config.toPOJO());

        // block until all subscribers are notified
        config.context.runOnPublishQueueAndWait(() -> {});
        assertEquals(1, nodeMerged.get());
        assertEquals(0, nodeUnchangedCount.get());
    }

    @Test
    void GIVEN_config_update_WHEN_merge_update_interleave_THEN_expect_merge_correct() throws Exception {
        // GIVEN
        // set up initial config and listeners
        String initConfig = "---\n"
                + "nodeToBeMerged:\n"
                + "  key1: val1\n"
                + "  nodeToBeReplaced:\n"
                + "    subNodeToBeRemoved: val\n"
                + "    subNodeToBeMerged:\n"
                + "      subKey1: subVal1\n"
                + "nodeToBeRemoved: val\n";

        String updateConfig = "---\n"
                + "nodeToBeMerged:\n"
                + "  key2: val2\n"
                + "  nodeToBeReplaced:\n"
                + "    subNodeToBeMerged:\n"
                + "      subKey2: subVal2\n"
                + "nodeToBeAdded: val\n";

        String expectedResult = "---\n"
                + "nodeToBeMerged:\n"
                + "  key1: val1\n"
                + "  key2: val2\n"
                + "  nodeToBeReplaced:\n"
                + "    subNodeToBeMerged:\n"
                + "      subKey1: subVal1\n"
                + "      subKey2: subVal2\n"
                + "nodeToBeAdded: val\n";

        Map<String, Object> initConfigMap;
        try (InputStream inputStream = new ByteArrayInputStream(initConfig.getBytes())) {
            initConfigMap = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }
        config.mergeMap(System.currentTimeMillis(), initConfigMap);
        config.context.runOnPublishQueueAndWait(() -> {});

        // WHEN
        Map<String, Object> updateConfigMap;
        try (InputStream inputStream = new ByteArrayInputStream(updateConfig.getBytes())) {
            updateConfigMap = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }

        UpdateBehaviorTree updateBehavior = new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE,
            createNewMap("nodeToBeMerged", new UpdateBehaviorTree(
                    UpdateBehaviorTree.UpdateBehavior.MERGE,
                    createNewMap("nodeToBeReplaced", new UpdateBehaviorTree(
                            UpdateBehaviorTree.UpdateBehavior.REPLACE,
                            createNewMap("subNodeToBeMerged",
                                    new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE))
                    ))
            ))
        );

        config.updateMap(System.currentTimeMillis(), updateConfigMap, updateBehavior);

        // THEN
        Map<String, Object> expectedConfig;
        try (InputStream inputStream = new ByteArrayInputStream(expectedResult.getBytes())) {
            expectedConfig = (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream);
        }
        assertEquals(expectedConfig, config.toPOJO());
    }

    private <T> Map<String, T> createNewMap(String key, T value) {
        Map<String, T> result = new HashMap<>();
        result.put(key, value);
        return result;
    }
}
