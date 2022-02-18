/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import vendored.com.microsoft.alm.storage.windows.internal.WindowsCredUtils;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsCredUtilsTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void GIVEN_windows_cred_utils_WHEN_crud_THEN_can_manage_credential_successfully() throws IOException {
        String key = "TestDomain\\unit-tester";
        String password = RandomStringUtils.randomAlphanumeric(20);
        String password2 = RandomStringUtils.randomAlphanumeric(20);

        // Create and read
        WindowsCredUtils.add(key, password.getBytes(WindowsCredUtils.getCharsetForSystem()));
        byte[] cred = WindowsCredUtils.read(key);
        assertArrayEquals(password.getBytes(WindowsCredUtils.getCharsetForSystem()), cred);

        // Update
        WindowsCredUtils.add(key, password2.getBytes(WindowsCredUtils.getCharsetForSystem()));
        byte[] cred2 = WindowsCredUtils.read(key);
        assertArrayEquals(password2.getBytes(WindowsCredUtils.getCharsetForSystem()), cred2);

        // Delete
        WindowsCredUtils.delete(key);
        IOException e = assertThrows(IOException.class, () -> WindowsCredUtils.read(key));
        assertThat(e.getCause().getMessage(), anyOf(containsString("Element not found"), containsString("Элемент не найден")));
    }
}
