/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.util.Coerce.toInt;
import static com.fasterxml.jackson.jr.ob.JSON.Feature.PRETTY_PRINT_OUTPUT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings({"PMD.DetachedTestCase", "PMD.UnusedLocalVariable"})
@ExtendWith(EGExtension.class)
public class ConfigurationTest {

    private Configuration config;

    @BeforeEach()
    void beforeEach() {
        config = new Configuration(new Context());
    }

    @AfterEach
    void afterEach() throws IOException {
        config.context.close();
    }

    //    @Test
    public void T1() {
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
        assertEquals(44, config.lookup("v").getOnce());
        assertEquals("v:44", config.lookup("v").toString());
    }

    //    @Test
    public void T2() {
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
        assertEquals(44, toInt(config.lookup("x", "y")));
        assertEquals("x.y:44", config.lookup("x", "y").toString());
    }

    //    @Test
    public void T3() {
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
    public void T4() throws Exception {
        T1();
        T2();
        T3();
        config.lookup("x", "a").withNewerValue(20, "hello");
        config.lookup("x", "b").withNewerValue(20, true);
        config.lookup("x", "c").withNewerValue(20, Math.PI);
        config.lookup("x", "d").withNewerValue(20, System.currentTimeMillis());
        Path p = Paths.get("/tmp/c.log");
        ConfigurationWriter.dump(config, p);
        assertEquals(config.getRoot(), config.getRoot());
        Configuration c2 = ConfigurationReader.createFromTLog(config.context, p);
        //            System.out.println(c2.hashCode() + " " + config.hashCode());
        //            System.out.println("Read: " + deepToString(c2.getRoot(), 99));
        assertEquals(44, c2.lookup("x", "z").getOnce());
        assertEquals(config, c2);
        //        config.lookupTopics("services").forEach(s -> System.out.println("Found service " + s.name));
        Topic nv = config.lookup("number");
    }

    @Test
    public void hmm() throws Throwable {
        try (InputStream inputStream = getClass().getResourceAsStream("test.yaml")) {
            assertNotNull(inputStream);
            //            System.out.println("resource: " + deepToString(inputStream, 200) + "\n\t" + getClass()
            //            .getName());
//            dump(testConfig,"Before");
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));
//            dump(testConfig,"After");
            Topics platforms = config.findTopics("platforms");
            //            platforms.forEachTopicSet(n -> System.out.println(n.name));

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
    public void GIVEN_empty_configuration_WHEN_topic_lookup_THEN_topic_created() {
        assertNull(config.find("root", "leaf"));
        Topic createdTopic = config.lookup("root", "leaf").dflt("defaultValue");
        assertEquals(createdTopic, config.find("root", "leaf"));
        assertEquals("defaultValue", createdTopic.getOnce());
    }

    @Test
    public void GIVEN_empty_configuration_WHEN_topics_lookup_THEN_topics_created() {
        assertNull(config.findTopics("root", "child"));
        Topics createdTopics = config.lookupTopics("root", "child");
        assertEquals(createdTopics, config.findTopics("root", "child"));
    }

    @Test
    public void GIVEN_config_with_subscribers_WHEN_topic_updated_THEN_subscribers_notified_with_changed_node()
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
    public void GIVEN_config_with_subscribers_WHEN_topic_removed_THEN_subscribers_notified() {
        Topic testTopic = config.lookup("a", "b", "c");
        AtomicInteger childNotified = new AtomicInteger(0);
        testTopic.subscribe((what, t) -> {
            if (what.equals(WhatHappened.removed)) {
                childNotified.incrementAndGet();
            }
        });
        AtomicInteger parentNotified = new AtomicInteger(0);
        config.findTopics("a", "b").subscribe((what, child) -> {
            if (child == testTopic && what.equals(WhatHappened.childRemoved)) {
                parentNotified.incrementAndGet();
            }
        });

        testTopic.remove();
        config.context.runOnPublishQueueAndWait(() -> {});

        assertEquals(1, childNotified.get());
        assertEquals(1, parentNotified.get());
        assertNull(config.find("a", "b", "c"));
    }

    @Test
    public void GIVEN_config_with_subscribers_WHEN_topics_removed_THEN_children_notified() {
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

        assertEquals(1, childNotified[1].get());
        assertEquals(1, childNotified[2].get());
    }
}
