/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigurationWriterTest {
    @TempDir
    protected Path tempDir;

    private Context context;

    @BeforeEach
    void beforeEach() {
        context = new Context();
    }

    @AfterEach
    void afterEach() throws IOException {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void GIVEN_config_with_configuration_writer_WHEN_config_changes_made_THEN_written_to_tlog() throws IOException {
        Path tlog = tempDir.resolve("c.tlog");
        Configuration config = new Configuration(context);

        try (ConfigurationWriter writer = ConfigurationWriter.logTransactionsTo(config, tlog)) {
            writer.flushImmediately(true);

            // Create a Topic somewhere in the hierarchy
            config.lookup("a.x", "b", "c", "d", "e").withValue("Some Val");
            // Create another Topic and use a different data type as the value (number)
            config.lookup("a.x", "b", "c.f", "d", "e2").withValue(2);
            context.runOnPublishQueueAndWait(() -> {
            }); // Block until publish queue is empty to ensure all changes have
            // been processed

            // Update the first Topic to show that the tlog can have multiple values over time
            config.lookup("a.x", "b", "c.f", "d", "e").withValue("New Val");
            // Create another Topic and use yet another data type (list)
            config.lookup("a.x", "b", "c.f", "d", "e3").withValue(Arrays.asList("1", "2", "3"));
            context.runOnPublishQueueAndWait(() -> {
            });

            // Assert that we can get back to the current in-memory state by reading the tlog
            Configuration readConfig = ConfigurationReader.createFromTLog(context, tlog);
            assertThat(readConfig.toPOJO(), is(config.toPOJO()));
        }
    }

    @Test
    void GIVEN_config_with_configuration_writer_WHEN_config_remove_made_THEN_written_to_tlog() throws IOException {
        Path tlog = tempDir.resolve("test_topic_removal.tlog");
        Configuration config = new Configuration(context);

        try (ConfigurationWriter writer = ConfigurationWriter.logTransactionsTo(config, tlog)) {
            writer.flushImmediately(true);

            // Create a Topic somewhere in the hierarchy
            config.lookup("a", "c").withValue("Some Val1");
            Topic t = config.lookup("a", "toBeRemoved").withValue("Some Val2");
            t.remove();

            Topics containerNode = config.lookupTopics("a", "containerToBeRemoved");
            containerNode.lookup("foo", "bar").withValue("dummy");
            containerNode.remove();

            context.runOnPublishQueueAndWait(() -> {
            }); // Block until publish queue is empty to ensure all changes have
            // been processed

            // Assert that we can get back to the current in-memory state by reading the tlog
            Configuration readConfig = ConfigurationReader.createFromTLog(context, tlog);
            context.runOnPublishQueueAndWait(() -> {
            }); // Block until publish queue is empty to ensure all changes have
            // been processed

            assertThat(readConfig.toPOJO(), is(config.toPOJO()));
            assertNull(readConfig.find("a", "toBeRemoved"));
            assertNull(readConfig.find("a", "containerToBeRemoved"));
        }
    }
}
