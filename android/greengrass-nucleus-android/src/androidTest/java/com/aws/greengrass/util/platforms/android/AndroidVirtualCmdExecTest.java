/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import androidx.test.platform.app.InstrumentationRegistry;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.managers.AndroidBaseApkManager;
import com.aws.greengrass.android.managers.AndroidBaseComponentManager;
import com.aws.greengrass.lifecyclemanager.Kernel;
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

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AndroidVirtualCmdExecTest {
    private Logger logger;
    private final String command = "#run_service Package.ClassName StartIntent";
    private final String packageName = "test";
    private AndroidPlatform platform;

    @BeforeEach
    public void setup() {
        logger = LogManager.getLogger(AndroidShellExecTest.class);

        AndroidContextProvider contextProvider = () -> InstrumentationRegistry.getInstrumentation().getTargetContext();
        platform = (AndroidPlatform) Platform.getInstance();

        AndroidServiceLevelAPI androidServiceLevelAPI = new AndroidServiceLevelAPI() {
            @Override
            public void terminate(int status) {
            }

            @Override
            public Kernel getKernel() {
                return null;
            }
        };

        platform.setAndroidAPIs(androidServiceLevelAPI,
                new AndroidBaseApkManager(contextProvider),
                new AndroidBaseComponentManager(contextProvider));
    }

    @Test
    void GIVEN_exec_WHEN_command_executed_in_background_THEN_success() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<String> stdoutMessages = new ArrayList<>();
        List<String> stderrMessages = new ArrayList<>();

        AndroidVirtualCmdExecution runner = platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger);
        runner.withOut(str -> stdoutMessages.add(str.toString()));
        runner.withErr(str -> stderrMessages.add(str.toString()));
        AndroidVirtualCmdExec exec = new AndroidVirtualCmdExec();
        exec.withVirtualCmd(runner, command);
        exec.background(exc -> done.countDown());

        // Wait for 1 second for command to finish
        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertEquals(0, stderrMessages.size());
        //wait for android impl, then replace to 1
        assertEquals(0, stdoutMessages.size());
        //wait for android impl
        //assertTrue(stdoutMessages.get(0).startsWith("#run_service"));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success() throws IOException, InterruptedException {
        CountDownLatch done = new CountDownLatch(1);

        AndroidVirtualCmdExecution runner = platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger);
        AndroidVirtualCmdExec exec = new AndroidVirtualCmdExec();
        exec.withVirtualCmd(runner, command);

        exec.background(exc -> done.countDown());
        assertNotNull(exec.getProcess());

        assertFalse(exec.isRunning());
        exec.close();
        assertFalse(exec.isRunning());
        // closing again should be no op, it should not throw
        exec.close();
        assertFalse(exec.isRunning());
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_correct_count_of_called_methods() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AndroidVirtualCmdExecution runnerSpy = spy(platform.getAndroidComponentManager()
                .getComponentRunner(command, packageName, logger));
        AndroidVirtualCmdExec exec = new AndroidVirtualCmdExec();

        exec.withVirtualCmd(runnerSpy, command);
        exec.background(exc -> done.countDown());
        // give time to finished background thread. when we close before thread finished
        Thread.sleep(1000);
        exec.close();

        // that because Package.ClassName does not exist, only startup and shutdown will be called.
        verify(runnerSpy, times(1)).startup();
        verify(runnerSpy, times(0)).run();
        verify(runnerSpy, times(1)).shutdown();
        assertEquals(0, done.getCount());
    }
}
