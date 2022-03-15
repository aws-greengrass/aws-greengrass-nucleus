/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import androidx.test.platform.app.InstrumentationRegistry;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.managers.AndroidBaseComponentManager;
import com.aws.greengrass.android.managers.AndroidBasePackageManager;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.android.managers.AndroidBaseComponentManager.SHUTDOWN_SERVICE_CMD_EXAMPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AndroidCallableExecTest {
    private Logger logger;
    private final String command = "#run_service [[[Package].ClassName] [StartIntent]]";
    private final String packageName = "test";
    private AndroidPlatform platform;

    @BeforeEach
    public void setup() {
        logger = LogManager.getLogger(AndroidShellExecTest.class);

        AndroidContextProvider contextProvider = () -> InstrumentationRegistry.getInstrumentation().getTargetContext();
        platform = (AndroidPlatform) Platform.getInstance();
        platform.setAndroidAPIs(status -> {
                },
                new AndroidBasePackageManager(contextProvider),
                new AndroidBaseComponentManager(contextProvider));
    }

    @Test
    void GIVEN_exec_WHEN_command_executed_in_background_THEN_success() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<String> stdoutMessages = new ArrayList<>();
        List<String> stderrMessages = new ArrayList<>();

        AndroidCallable runner = platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger);
        runner.withOut(str -> stdoutMessages.add(str.toString()));
        runner.withErr(str -> stderrMessages.add(str.toString()));
        AndroidCallableExec exec = new AndroidCallableExec();
        exec.withCallable(runner);
        exec.background(exc -> done.countDown());

        // Wait for 1 second for command to finish
        assertTrue(done.await(4, TimeUnit.SECONDS));
        assertEquals(0, stderrMessages.size());
        //wait for android impl, then replace to 1
        assertEquals(0, stdoutMessages.size());
        //wait for android impl
        //assertTrue(stdoutMessages.get(0).startsWith("#run_service"));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success() throws IOException, InterruptedException {
        CountDownLatch done = new CountDownLatch(1);

        AndroidCallable runner = platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger);
        AndroidCallableExec exec = new AndroidCallableExec();
        exec.withCallable(runner);
        exec.background(exc -> done.countDown());

        assertNotNull(exec.getProcess());
        assertTrue(exec.isRunning());
        exec.close();
        assertFalse(exec.isRunning());
        // closing again should be no op, it should not throw
        exec.close();
        assertFalse(exec.isRunning());
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_correct_count_of_called_methods() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AndroidCallable runnerSpy = spy(platform.getAndroidComponentManager()
                .getComponentRunner(command, packageName, logger));
        AndroidCallable closerSpy = spy(platform.getAndroidComponentManager()
                .getComponentStopper(SHUTDOWN_SERVICE_CMD_EXAMPLE, packageName, logger));
        AndroidCallableExec exec = new AndroidCallableExec();

        exec.withCallable(runnerSpy);
        exec.withClose(closerSpy);
        exec.background(exc -> done.countDown());
        exec.close();

        verify(runnerSpy, times(1)).call();
        verify(closerSpy, times(1)).call();
    }
}