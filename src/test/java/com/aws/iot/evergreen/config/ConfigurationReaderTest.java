/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(EGExtension.class)
public class ConfigurationReaderTest {
    // This is a generic test for configuration reader, hence declaring a namespace here
    // Deployment Config Merge is tested separately in integration test
    private static final String SKIP_MERGE_NAMESPACE_KEY = "notMerged";

    Configuration config = new Configuration(new Context());

    @AfterEach
    void afterEach() throws IOException {
        config.context.close();
    }

    @Test
    public void GIVEN_tlog_with_ignored_namespace_WHEN_tlog_merged_to_config_with_condition_THEN_ignored_value_not_updated()
            throws Exception {
        // Create this topic with temp value
        config.lookup(SERVICES_NAMESPACE_TOPIC, "YellowSignal",
                      SKIP_MERGE_NAMESPACE_KEY, "testTopic").withValue("Test");
        Path tlogPath = Paths.get(ConfigurationReaderTest.class.getResource("test.tlog").toURI());
        ConfigurationReader.mergeTlogIntoConfig(config, tlogPath, true,
                                                s -> !s.childOf(SKIP_MERGE_NAMESPACE_KEY));
        // Test tlog file has value set to "TLogValue"
        assertEquals("Test", config.lookup(SERVICES_NAMESPACE_TOPIC, "YellowSignal",
                                           SKIP_MERGE_NAMESPACE_KEY, "testTopic").getOnce());
    }

    @Test
    public void GIVEN_tlog_with_ignored_namespace_WHEN_tlog_merged_to_config_with_no_condition_THEN_all_values_updated()
            throws Exception {
        // Create this topic with temp value
        config.lookup(SERVICES_NAMESPACE_TOPIC, "YellowSignal",
                      SKIP_MERGE_NAMESPACE_KEY, "testTopic")
                                .withValue("Test");
        Path tlogPath = Paths.get(ConfigurationReaderTest.class.getResource("test.tlog").toURI());
        ConfigurationReader.mergeTlogIntoConfig(config, tlogPath, true, null);
        assertEquals("TLogValue", config.lookup(SERVICES_NAMESPACE_TOPIC, "YellowSignal",
                                                SKIP_MERGE_NAMESPACE_KEY, "testTopic").getOnce());
    }

    @Test
    public void GIVEN_tlog_WHEN_tlog_merged_to_config_with_forced_timestamp_THEN_topic_is_removed() throws Exception {
        // Create this topic with temp value
        config.lookup(SERVICES_NAMESPACE_TOPIC, "YellowSignal",
                      "lifecycle", "shutdown").withNewerValue(Long.MAX_VALUE, "Test");
        Path tlogPath = Paths.get(ConfigurationReaderTest.class.getResource("test.tlog").toURI());
        ConfigurationReader.mergeTlogIntoConfig(config, tlogPath, true, null);

        assertNull(config.find(SERVICES_NAMESPACE_TOPIC, "YellowSignal", "lifecycle", "shutdown"));
    }

    @Test
    public void GIVEN_tlog_WHEN_tlog_merged_to_config_with_smaller_timestamp_THEN_topic_is_removed() throws Exception {
        // Create this topic with temp value
        config.lookup(SERVICES_NAMESPACE_TOPIC, "YellowSignal",
                      "lifecycle", "shutdown").withNewerValue(1,"Test", true);
        Path tlogPath = Paths.get(this.getClass().getResource("test.tlog").toURI());
        ConfigurationReader.mergeTlogIntoConfig(config, tlogPath, false, null);
        assertNull(config.find(SERVICES_NAMESPACE_TOPIC, "YellowSignal", "lifecycle", "shutdown"));
    }

    @Test
    public void GIVEN_tlog_WHEN_tlog_merged_to_config_with_larger_timestamp_THEN_topic_is_not_removed() throws Exception {
        // Create this topic with temp value
        config.lookup(SERVICES_NAMESPACE_TOPIC, "YellowSignal",
                      "lifecycle", "shutdown").withNewerValue(Long.MAX_VALUE,"Test");
        Path tlogPath = Paths.get(this.getClass().getResource("test.tlog").toURI());
        ConfigurationReader.mergeTlogIntoConfig(config, tlogPath, false, null);

        Topic resultTopic = config.find(SERVICES_NAMESPACE_TOPIC, "YellowSignal", "lifecycle", "shutdown");
        assertEquals("Test", resultTopic.getOnce());
        assertEquals(Long.MAX_VALUE, resultTopic.getModtime());
    }

    @Test
    public void GIVEN_tlog_WHEN_merge_THEN_first_and_last_line_is_merged() throws Exception {
        Path tlogPath = Paths.get(this.getClass().getResource("test.tlog").toURI());
        ConfigurationReader.mergeTlogIntoConfig(config, tlogPath, false, null);

        assertEquals("firstline", config.find("test", "firstline").getOnce());
        assertEquals("lastline", config.find("test", "lastline").getOnce());
    }
}
