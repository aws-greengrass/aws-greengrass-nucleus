/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;

@ExtendWith({GGExtension.class})
@EnabledOnOs({OS.LINUX, OS.MAC})
class UnixPlatformTest   {

    private static String[] command = {"echo", "hello", "world"};

    @Test
    void GIVEN_no_user_and_no_group_WHEN_decorate_THEN_do_not_generate_sudo_with_user_and_group() {
        assertThat(new UnixPlatform.SudoDecorator().decorate(command),
                is(arrayContaining(command)));
    }

    @Test
    void GIVEN_user_and_group_WHEN_decorate_THEN_generate_sudo_with_user_and_group() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("foo")
                        .withGroup("bar")
                        .decorate(command),
                is(arrayContaining("sudo", "-n",  "-E", "-H", "-u", "foo", "-g", "bar", "--", "echo", "hello",
                        "world")));
    }

    @Test
    void GIVEN_numeric_user_and_group_WHEN_decorate_THEN_generate_sudo_with_prefix() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("100")
                        .withGroup("200")
                        .decorate(command),

                is(arrayContaining("sudo", "-n", "-E", "-H", "-u", "#100", "-g", "#200", "--", "echo", "hello",
                        "world")));
    }

    @Test
    void GIVEN_user_WHEN_decorate_THEN_generate_sudo_without_group() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("foo")
                        .decorate(command),
                is(arrayContaining("sudo", "-n", "-E", "-H", "-u", "foo", "--", "echo", "hello", "world")));
    }
}
