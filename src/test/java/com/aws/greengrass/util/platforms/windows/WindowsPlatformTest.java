/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.text.MatchesPattern.matchesPattern;

@ExtendWith({GGExtension.class})
@EnabledOnOs(OS.WINDOWS)
class WindowsPlatformTest {

    private static final String ADMINISTRATOR = "Administrator";

    @Test
    void GIVEN_command_WHEN_decorate_THEN_is_decorated() {
        assertThat(new WindowsPlatform.CmdDecorator()
                        .decorate("echo", "hello"),
                is(arrayContaining("cmd.exe", "/C", "echo", "hello")));
    }

    @Test
    void GIVEN_administrator_username_WHEN_lookupUser_THEN_get_correct_user() throws IOException {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        WindowsUserAttributes windowsUserAttributes = windowsPlatform.lookupUserByName(ADMINISTRATOR);
        // See the following for well known SIDs
        // https://docs.microsoft.com/en-us/troubleshoot/windows-server/identity/security-identifiers-in-windows
        assertThat(windowsUserAttributes.getPrincipalIdentifier(), matchesPattern("S-1-5-21-.*-500"));

        windowsUserAttributes = windowsPlatform.lookupUserByIdentifier(windowsUserAttributes.getPrincipalIdentifier());
        assertThat(windowsUserAttributes.getPrincipalName(), is(ADMINISTRATOR));
    }
}
