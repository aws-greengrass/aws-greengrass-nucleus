/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.Kernel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

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

    @Test
    void GIVEN_config_with_configuration_writer_WHEN_max_size_reached_THEN_auto_truncate()
            throws IOException, InterruptedException {
        Path tlog = tempDir.resolve("test_truncate.tlog");
        Path tlogOld = tempDir.resolve("test_truncate.tlog.old");
        Configuration config = new Configuration(context);
        Kernel mockKernel = mock(Kernel.class);
        doNothing().when(mockKernel).writeEffectiveConfigAsTransactionLog(any());
        context.put(Kernel.class, mockKernel);

        try (ConfigurationWriter writer = ConfigurationWriter.logTransactionsTo(config, tlog)) {
            writer.flushImmediately(true).withAutoTruncate(context).withMaxFileSize(120);
            assertFalse(Files.exists(tlogOld));

            Topic test1 = config.lookup("test1").withValue("1");
            test1.withNewerValue(System.currentTimeMillis(), "a longer string to exceed limit");
            // wait for truncation to complete
            Thread.sleep(500);
            context.runOnPublishQueueAndWait(() -> {});
            assertTrue(Files.exists(tlogOld));
            assertTrue(Files.exists(tlog));

            // now update should be written to the new tlog
            config.lookup("test2").withValue("new");
            context.runOnPublishQueueAndWait(() -> {});
        }
        Configuration newTlogConfig = ConfigurationReader.createFromTLog(new Context(), tlog);
        assertNull(newTlogConfig.find("test1"));
        assertEquals("new", newTlogConfig.find("test2").getOnce());
    }
}
