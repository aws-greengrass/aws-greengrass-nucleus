/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;

@ExtendWith({GGExtension.class})
public class UnixPlatformTest   {

    private static String[] command = {"echo", "hello", "world"};

    @Test
    public void GIVEN_no_user_and_no_group_WHEN_decorate_THEN_do_not_generate_sudo_with_user_and_group() {
        assertThat(new UnixPlatform.SudoDecorator().decorate(command),
                is(arrayContaining(command)));
    }

    @Test
    public void GIVEN_user_and_group_WHEN_decorate_THEN_generate_sudo_with_user_and_group() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("foo")
                        .withGroup("bar")
                        .decorate(command),
                is(arrayContaining("sudo", "-E", "-u", "foo", "-g", "bar", "--", "echo", "hello", "world")));
    }

    @Test
    public void GIVEN_numeric_user_and_group_WHEN_decorate_THEN_generate_sudo_with_prefix() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("100")
                        .withGroup("200")
                        .decorate(command),
                is(arrayContaining("sudo", "-E", "-u", "\\#100", "-g", "\\#200", "--", "echo", "hello", "world")));
    }

    @Test
    public void GIVEN_user_WHEN_decorate_THEN_generate_sudo_without_group() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("foo")
                        .decorate(command),
                is(arrayContaining("sudo", "-E", "-u", "foo", "--", "echo", "hello", "world")));
    }
}
