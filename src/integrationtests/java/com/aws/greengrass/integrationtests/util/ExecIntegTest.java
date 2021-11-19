/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.util;

import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ExecIntegTest extends BaseITCase {

    private static final String TEST_ENV_ENTRY = "integ_test_env";
    private static final String SYSTEM_ENV_REG_KEY = "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment";

    private static String testUserEnvRegKey;

    @AfterAll
    static void cleanup() throws IOException, InterruptedException {
        deleteRegistryEntry(SYSTEM_ENV_REG_KEY, TEST_ENV_ENTRY);
        if (testUserEnvRegKey != null) {
            deleteRegistryEntry(testUserEnvRegKey, TEST_ENV_ENTRY);
        }
    }

    private static void deleteRegistryEntry(String key, String entry) throws IOException, InterruptedException {
        // reg command reference https://docs.microsoft.com/en-us/windows-server/administration/windows-commands/reg
        Process p = new ProcessBuilder("reg", "delete", key, "/f", "/v", entry).start();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("delete registry entry timeout");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void GIVEN_windows_exec_WHEN_set_env_vars_from_multiple_sources_THEN_precedence_is_correct()
            throws IOException, InterruptedException {
        // Check setting default env works
        // setDefaultEnv is static and will persist throughout this test.
        String expectedVal = "Exec default";
        Exec.setDefaultEnv(TEST_ENV_ENTRY, expectedVal);
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            String output = exec.cd("C:\\")  // cd in case test user doesn't have permission to access current directory
                    .withUser(WINDOWS_TEST_UESRNAME)
                    .withShell("echo", "%" + TEST_ENV_ENTRY + "%")
                    .execAndGetStringOutput();
            assertEquals(expectedVal, output);
        }

        // Set system-level env var via registry. should override the default
        expectedVal = "system env var";
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            String output = exec
                    .withShell("reg", "add", SYSTEM_ENV_REG_KEY,
                            "/f", "/v", TEST_ENV_ENTRY, "/t", "REG_SZ", "/d", expectedVal)
                    .execAndGetStringOutput();
            assertThat(output, containsString("completed successfully"));
        }

        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            String output = exec.cd("C:\\").withUser(WINDOWS_TEST_UESRNAME)
                    .withShell("echo", "%" + TEST_ENV_ENTRY + "%").execAndGetStringOutput();
            assertEquals(expectedVal, output);
        }

        // Set user-level env var via registry. should override the system-level setting
        // FYI: Usually, user-level setting has higher precedence than system-level. PATH is special.
        // User PATH is appended to system-level PATH by windows.
        expectedVal = "user account env var";
        String testUserSid = Advapi32Util.getAccountByName(WINDOWS_TEST_UESRNAME).sidString;
        testUserEnvRegKey = String.format("HKU\\%s\\Environment", testUserSid);
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            String output = exec
                    .cd("C:\\")
                    .withUser(WINDOWS_TEST_UESRNAME)
                    .withShell("reg", "add", testUserEnvRegKey, "/f", "/v", TEST_ENV_ENTRY, "/t", "REG_SZ", "/d", expectedVal)
                    .execAndGetStringOutput();
            assertThat(output, containsString("completed successfully"));
        }

        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            String output = exec.cd("C:\\").withUser(WINDOWS_TEST_UESRNAME)
                    .withShell("echo", "%" + TEST_ENV_ENTRY + "%").execAndGetStringOutput();
            assertEquals(expectedVal, output);
        }

        // Use setenv, which overrides everything
        expectedVal = "setenv override";
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            String output = exec.cd("C:\\").withUser(WINDOWS_TEST_UESRNAME).setenv(TEST_ENV_ENTRY, expectedVal)
                    .withShell("echo", "%" + TEST_ENV_ENTRY + "%").execAndGetStringOutput();
            assertEquals(expectedVal, output);
        }
    }
}
