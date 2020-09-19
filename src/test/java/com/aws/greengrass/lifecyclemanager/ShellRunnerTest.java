/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShellRunnerTest extends GGServiceTestUtil {

    @Mock
    private Topic uniqueId;

    @Mock
    private Kernel kernel;

    @TempDir
    protected Path tempDir;

    private GreengrassService greengrassService;

    @BeforeEach
    void beforeEach() {
        Topics config = initializeMockedConfig();
        Topics servicePrivateTopics = mock(Topics.class);
        Topic mockTopic = mock(Topic.class);

        when(config.lookupTopics(GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC)).thenReturn(servicePrivateTopics);
        when(servicePrivateTopics.findLeafChild(SERVICE_UNIQUE_ID_KEY)).thenReturn(uniqueId);
        when(servicePrivateTopics.createLeafChild(anyString())).thenReturn(mockTopic);
        when(mockTopic.withParentNeedsToKnow(false)).thenReturn(mockTopic);
        when(mockTopic.withValue(any())).thenReturn(mockTopic);
        when(mockTopic.addValidator(any())).thenReturn(mockTopic);

        when(kernel.getWorkPath()).thenReturn(tempDir);
        greengrassService = new GreengrassService(config);
    }

    @Test
    void GIVEN_shell_command_WHEN_setup_THEN_sets_exec_cwd_to_work_path_with_service() throws Exception {
        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "echo hi", greengrassService)) {
                assertEquals(kernel.getWorkPath().resolve(greengrassService.getName()).toFile().toString(),
                        exec.cwd().toString());
            }
        }
    }

    @Test
    void GIVEN_shell_command_WHEN_run_in_foreground_THEN_succeeds() throws Exception {
        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "echo hi", greengrassService)) {
                boolean ok = shellRunner.successful(exec, "note", null, greengrassService);
                assertTrue(ok);
                assertFalse(exec.isRunning());
            }
        }
    }

    @Test
    void GIVEN_shell_command_WHEN_run_in_background_THEN_succeeds() throws Exception {
        final AtomicInteger exitCode = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        IntConsumer background = (value) -> {
            exitCode.set(value);
            latch.countDown();
        };

        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "echo 0", greengrassService)) {
                boolean ok = shellRunner.successful(exec, "note", background, greengrassService);
                assertTrue(ok);
                assertTrue(latch.await(2, TimeUnit.SECONDS));
                assertEquals(0, exitCode.get());
                assertFalse(exec.isRunning());
            }
        }
    }

    @Test
    void GIVEN_shell_command_that_doesnt_exist_WHEN_run_in_foreground_THEN_fails() throws Exception {
        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "there_is_no_such_program", greengrassService)) {
                boolean ok = shellRunner.successful(exec, "note", null, greengrassService);
                assertFalse(ok);
                assertFalse(exec.isRunning());
            }
        }
    }

    @Test
    void GIVEN_shell_command_that_doesnt_exist_WHEN_run_in_background_THEN_fails() throws Exception {
        final AtomicInteger exitCode = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        IntConsumer background = (value) -> {
            exitCode.set(value);
            latch.countDown();
        };

        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "there_is_no_such_program", greengrassService)) {
                boolean ok = shellRunner.successful(exec, "note", background, greengrassService);
                assertTrue(ok); // when runs in background, always return true
                assertTrue(latch.await(2, TimeUnit.SECONDS));
                assertEquals(Platform.getInstance().exitCodeWhenCommandDoesNotExist(), exitCode.get());
                assertFalse(exec.isRunning());
            }
        }
    }
}
