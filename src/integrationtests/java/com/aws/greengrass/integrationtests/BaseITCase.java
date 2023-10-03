/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests;


import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.UniqueRootPathExtension;
import com.aws.greengrass.testing.TestFeatureParameterInterface;
import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.Utils;
import com.sun.jna.LastErrorException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkSystemSetting;
import vendored.com.microsoft.alm.storage.windows.internal.WindowsCredUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.FALLBACK_DEFAULT_REGION;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.mqttclient.MqttClient.CONNECT_LIMIT_PERMITS_FEATURE;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This class is a base IT case to simplify the setup for integration tests.
 * <p>
 * It creates a temp directory and sets it to "root" before each @Test.
 * <p>
 * However, individual integration test could override the setup or just set up without extending this.
 */
@ExtendWith({GGExtension.class, UniqueRootPathExtension.class, MockitoExtension.class})
public class BaseITCase {
    protected static final String WINDOWS_TEST_UESRNAME = "integ-tester";
    protected static final String WINDOWS_TEST_UESRNAME_2 = "integ-tester-2";
    public static final String WINDOWS_TEST_PASSWORD = "hunter2HUNTER@";

    protected Path tempRootDir;
    private static Context testContext;

    @Mock
    protected static TestFeatureParameterInterface DEFAULT_HANDLER;

    @BeforeEach
    void setRootDir() {
        tempRootDir = Paths.get(System.getProperty("root"));
        LogConfig.getRootLogConfig().reset();

        lenient().when(DEFAULT_HANDLER.retrieveWithDefault(any(), any(), any())).thenAnswer((v) -> v.getArguments()[2]);
        lenient().when(DEFAULT_HANDLER.retrieveWithDefault(eq(Double.class), eq(CONNECT_LIMIT_PERMITS_FEATURE), any()))
                .thenReturn(Double.MAX_VALUE);
        TestFeatureParameters.clearHandlerCallbacks();
        TestFeatureParameters.internalEnableTestingFeatureParameters(DEFAULT_HANDLER);

        // Tests will always fail when the region isn't set. So just set it here for ease of testing with IntelliJ
        if (System.getenv(SdkSystemSetting.AWS_REGION.environmentVariable()) == null) {
            System.setProperty(SdkSystemSetting.AWS_REGION.property(), FALLBACK_DEFAULT_REGION);
        }
    }

    @AfterEach
    void shutdownTestFeatureParameters() {
        TestFeatureParameters.clearHandlerCallbacks();
        TestFeatureParameters.internalDisableTestingFeatureParameters();
    }

    @BeforeAll
    static void setup() throws IOException, InterruptedException {
        if (!PlatformResolver.isWindows) {
            return;
        }
        // To test runWith on Windows, need to prepare user and credential
        createWindowsTestUser(WINDOWS_TEST_UESRNAME, WINDOWS_TEST_PASSWORD);
        createWindowsTestUser(WINDOWS_TEST_UESRNAME_2, WINDOWS_TEST_PASSWORD);
        WindowsCredUtils.add(WINDOWS_TEST_UESRNAME,
                WINDOWS_TEST_PASSWORD.getBytes(WindowsCredUtils.getCharsetForSystem()));
        WindowsCredUtils.add(WINDOWS_TEST_UESRNAME_2,
                WINDOWS_TEST_PASSWORD.getBytes(WindowsCredUtils.getCharsetForSystem()));
        // mock runas path
        KernelAlternatives mockKernelAlts = mock(KernelAlternatives.class);
        when(mockKernelAlts.getBinDir()).thenReturn(Paths.get("scripts").toAbsolutePath());
        testContext = new Context();
        testContext.put(KernelAlternatives.class, mockKernelAlts);
    }

    @AfterAll
    static void cleanup() throws IOException, InterruptedException {
        if (!PlatformResolver.isWindows) {
            return;
        }
        deleteWindowsTestUser(WINDOWS_TEST_UESRNAME);
        deleteWindowsTestUser(WINDOWS_TEST_UESRNAME_2);
        try {
            WindowsCredUtils.delete(WINDOWS_TEST_UESRNAME);
            WindowsCredUtils.delete(WINDOWS_TEST_UESRNAME_2);
        } catch (IOException e) {
            // Don't fail if the credential being deleted doesn't exist
            Throwable cause = e.getCause();
            if (!(cause != null
                    && cause instanceof LastErrorException
                    && Utils.isNotEmpty(cause.getMessage())
                    && cause.getMessage().contains("Element not found")
            )) {
                throw e;
            }
        }
        testContext.close();
    }

    public static void setDeviceConfig(Kernel kernel, String key, Number value) {
        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME,
                CONFIGURATION_CONFIG_KEY, key).withValue(value);
    }

    public static void createWindowsTestUser(String username, String password)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder().command("net", "user", username, password, "/add").start();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("create user timeout");
        }
        if (p.exitValue() != 0) {
            String error = Utils.inputStreamToString(p.getErrorStream());
            if (!error.contains("The account already exists")) {
                fail("Failed to create user: " + username + ". " + error);
            }
        }
    }

    public static void deleteWindowsTestUser(String username) throws IOException, InterruptedException {
        Process p = new ProcessBuilder().command("net", "user", username, "/delete").start();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("delete user timeout");
        }
        // Delete test user's home directory
        FileUtils.deleteQuietly(new File("C:\\Users\\" + username));
    }
}
