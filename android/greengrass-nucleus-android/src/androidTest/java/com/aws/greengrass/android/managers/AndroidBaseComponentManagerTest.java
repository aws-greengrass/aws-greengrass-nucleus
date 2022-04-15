/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import androidx.test.platform.app.InstrumentationRegistry;

import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.aws.greengrass.util.platforms.android.AndroidServiceLevelAPI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

public class AndroidBaseComponentManagerTest {

    private Logger logger;
    private AndroidPlatform platform;
    private final String packageName = "PackageName";

    @BeforeEach
    public void setup() {
        logger = LogManager.getLogger(AndroidBaseComponentManagerTest.class);

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
                new AndroidBasePackageManager(contextProvider),
                new AndroidBaseComponentManager(contextProvider));
    }

    @Test
    void GIVEN_component_runner_WHEN_running_command_THEN_success() {
        String command = "#run_service PackageName.ClassName StartIntentName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger));
        String command2 = "#run_service .ClassName StartIntentName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command2, packageName, logger));
        String command3 = "#run_service .ClassName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command3, packageName, logger));
        String command4 = "#run_service";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command4, packageName, logger));
        String command5 = "#run_service -- 500";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command5, packageName, logger));
        String command6 = "#run_service --";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command6, packageName, logger));
    }

    @Test
    void GIVEN_component_runner_WHEN_running_command_THEN_exception() {
        String command = "#run_service PackageName.ClassName StartIntentName extraWrong";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger));
        String command2 = "";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command2, packageName, logger));
        String command3 = "#startup_service";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command3, packageName, logger));
    }

    @Test
    void GIVEN_component_starter_and_stopper_WHEN_running_command_THEN_success() {
        String command = "#startup_service PackageName.ClassName StartIntentName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command, packageName, logger));
        String command2 = "#startup_service .ClassName StartIntentName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command2, packageName, logger));
        String command3 = "#startup_service";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command3, packageName, logger));
        String command4 = "#startup_service .ClassName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command4, packageName, logger));
        String command5 = "#startup_service -- 500";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command5, packageName, logger));
        String command6 = "#startup_service --";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command6, packageName, logger));
    }

    @Test
    void GIVEN_component_starter_and_stopper_WHEN_running_command_THEN_exception() {
        String command = "#startup_service PackageName.ClassName StartIntentName extraWrong";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command, packageName, logger));
        String command2 = "";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command2, packageName, logger));
        String command3 = "#run_service";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStarterAndStopper(command3, packageName, logger));
    }

    @Test
    void GIVEN_component_stopper_WHEN_running_command_THEN_success() {
        String command = "#shutdown_service PackageName.ClassName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command, packageName, logger));
        String command2 = "#shutdown_service .ClassName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command2, packageName, logger));
        String command3 = "#shutdown_service";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command3, packageName, logger));
    }

    @Test
    void GIVEN_component_stopper_WHEN_running_command_THEN_exception() {
        String command = "#shutdown_service PackageName.ClassName extraWrong";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command, packageName, logger));
        String command2 = "";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command2, packageName, logger));
        String command3 = "#run_service";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command3, packageName, logger));
        String command4 = "#shutdown_service PackageName.ClassName -- 500";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command4, packageName, logger));
        String command5 = "#shutdown_service PackageName.ClassName --";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command5, packageName, logger));
    }

    @Test
    void GIVEN_component_runner_WHEN_call_THEN_exception() {
        String command = "#run_service PackageName.ClassName StartIntentName";
        AndroidCallable androidCallable =
                platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger);
        assertThrows(RuntimeException.class, (Executable) androidCallable::call);
    }

    @Test
    void GIVEN_component_starter_and_stopper_WHEN_call_THEN_result() {
        String command = "#startup_service PackageName.ClassName StartIntentName";
        Pair<AndroidCallable, AndroidCallable> pair =
                platform.getAndroidComponentManager().getComponentStarterAndStopper(command, packageName, logger);
        assertThrows(RuntimeException.class, (Executable) pair.getLeft()::call);
        assertDoesNotThrow((Executable) (Executable) pair.getRight()::call);
    }

    @Test
    void GIVEN_component_starter_stopper_WHEN_call_THEN_result() {
        String command = "#shutdown_service PackageName.ClassName";
        AndroidCallable androidCallable =
                platform.getAndroidComponentManager().getComponentStopper(command, packageName, logger);
        assertDoesNotThrow((Executable) androidCallable::call);
    }
}
