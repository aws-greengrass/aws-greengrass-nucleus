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
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import vendored.com.microsoft.alm.storage.windows.internal.WindowsCredUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This class is a base IT case to simplify the setup for integration tests.
 * <p>
 * It creates a temp directory and sets it to "root" before each @Test.
 * <p>
 * However, individual integration test could override the setup or just set up without extending this.
 */
@ExtendWith({GGExtension.class, UniqueRootPathExtension.class})
public class BaseITCase {
    protected static final String WINDOWS_TEST_UESRNAME = "integ-tester";
    protected static final String WINDOWS_TEST_UESRNAME_2 = "integ-tester-2";
    protected static final String WINDOWS_TEST_PASSWORD = "hunter2HUNTER@";

    protected Path tempRootDir;
    private static Context testContext;

    @BeforeEach
    void setRootDir() {
        tempRootDir = Paths.get(System.getProperty("root"));
        LogConfig.getRootLogConfig().reset();
    }

    @BeforeAll
    static void setup() throws IOException, InterruptedException {
        if (!PlatformResolver.isWindows) {
            return;
        }
        // To test runWith on Windows, need to prepare user and credential
        createWindowsTestUser(WINDOWS_TEST_UESRNAME, WINDOWS_TEST_PASSWORD);
        createWindowsTestUser(WINDOWS_TEST_UESRNAME_2, WINDOWS_TEST_PASSWORD);
        WindowsCredUtils.add(WINDOWS_TEST_UESRNAME, WINDOWS_TEST_PASSWORD.getBytes(StandardCharsets.UTF_8));
        WindowsCredUtils.add(WINDOWS_TEST_UESRNAME_2, WINDOWS_TEST_PASSWORD.getBytes(StandardCharsets.UTF_8));
        // mock runas path
        KernelAlternatives mockKernelAlts = mock(KernelAlternatives.class);
        when(mockKernelAlts.getBinDir()).thenReturn(Paths.get("scripts").toAbsolutePath());
        testContext = new Context();
        testContext.put(KernelAlternatives.class, mockKernelAlts);
        mockRunasExePath();
    }

    @AfterAll
    static void cleanup() throws IOException, InterruptedException {
        if (!PlatformResolver.isWindows) {
            return;
        }
        deleteWindowsTestUser(WINDOWS_TEST_UESRNAME);
        deleteWindowsTestUser(WINDOWS_TEST_UESRNAME_2);
        WindowsCredUtils.delete(WINDOWS_TEST_UESRNAME);
        WindowsCredUtils.delete(WINDOWS_TEST_UESRNAME_2);
        testContext.close();
    }

    public static void setDeviceConfig(Kernel kernel, String key, Number value) {
        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME,
                CONFIGURATION_CONFIG_KEY, key).withValue(value);
    }

    /**
     * Sets the Platform.context static field to be a mocked one that'll return correct runas exe path under unit/integ
     * testing scenarios. This will be reset when a new Kernel is created.
     */
    public static void mockRunasExePath() {
        if (PlatformResolver.isWindows) {
            Platform.setContext(testContext);
        }
    }

    private static void createWindowsTestUser(String username, String password)
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

    private static void deleteWindowsTestUser(String username) throws IOException, InterruptedException {
        Process p = new ProcessBuilder().command("net", "user", username, "/delete").start();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("delete user timeout");
        }
        // Delete test user's home directory
        FileUtils.deleteQuietly(new File("C:\\Users\\" + username));
    }
}
