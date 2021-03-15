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
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@ExtendWith({GGExtension.class})
@EnabledOnOs(OS.WINDOWS)
class WindowsPlatformTest {

    @Test
    void GIVEN_command_WHEN_decorate_THEN_is_decorated() {
        assertThat(new WindowsPlatform.CmdDecorator()
                        .decorate("echo", "hello"),
                is(arrayContaining("cmd.exe", "/C", "echo", "hello")));
    }

    @Test
    void GIVEN_administrator_username_WHEN_check_user_exists_THEN_return_true() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        assertThat(windowsPlatform.userExists("Administrator"), is(true));
    }

    @Test
    void GIVEN_random_string_as_username_WHEN_check_user_exists_THEN_return_false() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        assertThat(windowsPlatform.userExists(UUID.randomUUID().toString()), is(false));
    }

    @Test
    void WHEN_lookup_current_user_THEN_get_user_attributes() throws IOException {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        WindowsUserAttributes windowsUserAttributes = windowsPlatform.lookupCurrentUser();
        assertThat(windowsUserAttributes.getPrincipalName(), not(emptyOrNullString()));
        assertThat(windowsUserAttributes.getPrincipalIdentifier(), not(emptyOrNullString()));
    }
}
