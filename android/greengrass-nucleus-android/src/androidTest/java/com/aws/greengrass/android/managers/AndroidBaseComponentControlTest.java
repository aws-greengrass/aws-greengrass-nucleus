/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import static com.aws.greengrass.android.component.utils.Constants.ACTION_START_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.EXTRA_ARGUMENTS;
import static com.aws.greengrass.android.component.utils.Constants.EXTRA_COMPONENT_ENVIRONMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import androidx.test.platform.app.InstrumentationRegistry;

import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

@ExtendWith({MockitoExtension.class})
public class AndroidBaseComponentControlTest {
    private static Logger logger = LogManager.getLogger(AndroidBasePackageManagerTest.class);
    private AndroidPlatform platform;
    private List<ResolveInfo> matches;
    private Consumer<CharSequence> stdoutConsumer;
    private Consumer<CharSequence> stderrConsumer;

    @Mock
    Context context;
    @Mock
    PackageManager packageManager;

    @BeforeEach
    public void setup() throws NoSuchMethodException, InterruptedException {
        when(context.getFilesDir()).thenReturn(InstrumentationRegistry.
                getInstrumentation().getContext().getFilesDir());
        when(context.getPackageManager()).thenReturn(packageManager);
        matches = mock(List.class);
        when(packageManager.queryIntentServices(any(), anyInt())).thenReturn(matches);

        AndroidContextProvider contextProvider = () -> context;

        platform = (AndroidPlatform) Platform.getInstance();
        platform.setAndroidAPIs(status -> {
                },
                new AndroidBasePackageManager(contextProvider),
                new AndroidBaseComponentManager(contextProvider));

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        stdoutConsumer = stdout::append;
        stderrConsumer = stderr::append;
    }

    @Test
    void GIVEN_component_manager_WHEN_run_service_THEN_intent_sent() throws InterruptedException {
        when(matches.size()).thenReturn(1);
        AndroidBaseComponentControl componentControl = new AndroidBaseComponentControl(
                () -> context,
                "test.package",
                "test.package.TestClass",
                ACTION_START_COMPONENT,
                new String[]{},
                new HashMap<String, String>(),
                logger,
                stdoutConsumer,
                stderrConsumer
        );
        try {
            componentControl.run(1000);
        } catch (RuntimeException ex) {
            assertEquals("Couldn't start Android component", ex.getMessage());
        }
        ArgumentCaptor<Intent> argument = ArgumentCaptor.forClass(Intent.class);
        verify(context, atMostOnce()).startForegroundService(argument.capture());
        Intent startForegroundIntent = argument.getValue();
        assertEquals(ACTION_START_COMPONENT, startForegroundIntent.getAction());
        assertEquals("test.package",
                startForegroundIntent.getComponent().getPackageName());
        assertEquals("test.package.TestClass",
                startForegroundIntent.getComponent().getClassName());
    }

    @Test
    void GIVEN_component_manager_WHEN_run_service_with_environment_and_parameters_THEN_intent_sent()
            throws InterruptedException {
        HashMap<String, String> prepairedEnv = new HashMap<String, String>();
        prepairedEnv.put("TEST", "test_value");
        when(matches.size()).thenReturn(1);
        AndroidBaseComponentControl componentControl = new AndroidBaseComponentControl(
                () -> context,
                "test.package",
                "test.package.TestClass",
                ACTION_START_COMPONENT,
                new String[]{"10000"},
                prepairedEnv,
                logger,
                stdoutConsumer,
                stderrConsumer
        );
        assertThrows(RuntimeException.class, () -> componentControl.run(1000));

        ArgumentCaptor<Intent> argument = ArgumentCaptor.forClass(Intent.class);
        verify(context, atMostOnce()).startForegroundService(argument.capture());
        Intent startForegroundIntent = argument.getValue();

        assertTrue(startForegroundIntent.hasExtra(EXTRA_ARGUMENTS));
        String[] arguments = startForegroundIntent.getStringArrayExtra(EXTRA_ARGUMENTS);
        assertEquals(1, arguments.length);
        assertEquals("10000", arguments[0]);

        assertTrue(startForegroundIntent.hasExtra(EXTRA_COMPONENT_ENVIRONMENT));
        HashMap<String, String> sentEnv =
                (HashMap<String, String>) startForegroundIntent.getSerializableExtra(EXTRA_COMPONENT_ENVIRONMENT);
        assertTrue(sentEnv.containsKey("TEST"));
        assertEquals("test_value", sentEnv.get("TEST"));
    }

    @Test
    void GIVEN_component_manager_WHEN_run_nonexisting_service_THEN_intent_not_sent()
            throws InterruptedException {
        when(matches.size()).thenReturn(0);
        AndroidBaseComponentControl componentControl = new AndroidBaseComponentControl(
                () -> context,
                "test.package",
                "test.package.TestClass",
                ACTION_START_COMPONENT,
                new String[]{},
                new HashMap<String, String>(),
                logger,
                stdoutConsumer,
                stderrConsumer
        );
        try {
            componentControl.run(1000);
        } catch (RuntimeException ex) {
            assertEquals(
                    "Service with package test.package and class test.package.TestClass couldn't found",
                    ex.getMessage());
        }
        verify(context, never()).startForegroundService(any());
    }
}
