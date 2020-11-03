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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
            context.waitForPublishQueueToClear(); // Block until publish queue is empty to ensure all changes have
            // been processed

            // Update the first Topic to show that the tlog can have multiple values over time
            config.lookup("a.x", "b", "c.f", "d", "e").withValue("New Val");
            // Create another Topic and use yet another data type (list)
            config.lookup("a.x", "b", "c.f", "d", "e3").withValue(Arrays.asList("1", "2", "3"));
            // Create null-valued node
            config.lookup("a.x", "b", "c.f", "d", "e5").withValue((String) null);
            // Create empty map
            config.lookupTopics("x", "y", "z").remove();
            context.waitForPublishQueueToClear();

            // Assert that we can get back to the current in-memory state by reading the tlog
            Configuration readConfig = ConfigurationReader.createFromTLog(context, tlog);
            assertThat(readConfig.toPOJO(), is(config.toPOJO()));

            Files.deleteIfExists(tlog);
            ConfigurationWriter.dump(config, tlog);
            readConfig = ConfigurationReader.createFromTLog(context, tlog);
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

            context.waitForPublishQueueToClear(); // Block until publish queue is empty to ensure all changes have
            // been processed

            // Assert that we can get back to the current in-memory state by reading the tlog
            Configuration readConfig = ConfigurationReader.createFromTLog(context, tlog);
            context.waitForPublishQueueToClear(); // Block until publish queue is empty to ensure all changes have
            // been processed

            assertThat(readConfig.toPOJO(), is(config.toPOJO()));
            assertNull(readConfig.find("a", "toBeRemoved"));
            assertNull(readConfig.find("a", "containerToBeRemoved"));
        }
    }

    @Test
    void GIVEN_config_with_configuration_writer_WHEN_max_size_reached_THEN_auto_truncate()
            throws IOException {
        Path tlog = tempDir.resolve("test_truncate.tlog");
        Configuration config = new Configuration(context);
        Kernel mockKernel = mock(Kernel.class);
        doNothing().when(mockKernel).writeEffectiveConfigAsTransactionLog(any());
        context.put(Kernel.class, mockKernel);

        try (ConfigurationWriter writer = ConfigurationWriter.logTransactionsTo(config, tlog).flushImmediately(true)
                .withAutoTruncate(context)) {
            // make some changes to trigger truncate
            config.lookup("test0").withValue("0");
            context.runOnPublishQueueAndWait(() -> {
                writer.withMaxEntries(2);
                config.lookup("test0").withValue("exceed limit");
                context.runOnPublishQueue(() -> writer.withMaxEntries(100));
            });
            // wait for truncate to finish
            context.waitForPublishQueueToClear();
            // now test1 should be written to the new tlog
            config.lookup("test1").withValue("1");
            context.waitForPublishQueueToClear();
            // verify
            Configuration newTlogConfig1 = ConfigurationReader.createFromTLog(context, tlog);
            assertNull(newTlogConfig1.find("test0"));
            assertEquals("1", newTlogConfig1.find("test1").getOnce());

            // trigger truncate again to make sure it succeeds reliably
            context.runOnPublishQueueAndWait(() -> {
                writer.withMaxEntries(2);
                config.lookup("test1").withValue("exceed limit");
                context.runOnPublishQueue(() -> writer.withMaxEntries(100));
            });

            // wait for truncate to finish
            context.waitForPublishQueueToClear();
            // now test2 should be written to the new tlog
            config.lookup("test2").withValue("2");
            context.waitForPublishQueueToClear();
        }
        // verify
        Configuration newTlogConfig2 = ConfigurationReader.createFromTLog(context, tlog);
        assertNull(newTlogConfig2.find("test1"));
        assertEquals("2", newTlogConfig2.find("test2").getOnce());
    }

    @Test
    void GIVEN_config_with_configuration_writer_WHEN_truncate_and_write_effective_config_failed_THEN_recover()
            throws IOException {
        Path tlog = tempDir.resolve("test_truncate.tlog");
        Configuration config = new Configuration(context);
        Kernel mockKernel = mock(Kernel.class);
        doThrow(new IOException("test")).when(mockKernel).writeEffectiveConfigAsTransactionLog(any());
        context.put(Kernel.class, mockKernel);

        try (ConfigurationWriter writer = ConfigurationWriter.logTransactionsTo(config, tlog).flushImmediately(true)
                .withAutoTruncate(context)) {
            // make some changes to trigger truncate
            config.lookup("test1").withValue("1");
            context.waitForPublishQueueToClear();
            context.runOnPublishQueueAndWait(() -> {
                writer.withMaxEntries(2);
                config.lookup("test1").withValue("exceed limit");
                context.runOnPublishQueue(() -> writer.withMaxEntries(100));
            });

            context.waitForPublishQueueToClear();
            // truncate should fail and recover, keep using the old tlog
            config.lookup("test2").withValue("new");
            context.waitForPublishQueueToClear();
        }
        // verify values
        Configuration newTlogConfig = ConfigurationReader.createFromTLog(context, tlog);
        assertEquals("exceed limit", newTlogConfig.find("test1").getOnce());
        assertEquals("new", newTlogConfig.find("test2").getOnce());
    }
}
