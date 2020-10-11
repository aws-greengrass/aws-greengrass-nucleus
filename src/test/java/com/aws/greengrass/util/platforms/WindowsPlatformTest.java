/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
public class WindowsPlatformTest {

    @Test
    public void GIVEN_no_user_WHEN_decorate_THEN_do_not_generate_runas() {
        assertThat(new WindowsPlatform.RunAsDecorator()
                        .decorate("echo", "hello"),
                is(arrayContaining("echo", "hello")));
    }
    @Test
    public void GIVEN_user_WHEN_decorate_THEN_generate_runas() {
        assertThat(new WindowsPlatform.RunAsDecorator()
                        .withUser("foo@bar")
                        .decorate("echo", "hello"),
                is(arrayContaining("runas", "/user:foo@bar", "echo hello")));
    }

    @Test
    public void GIVEN_group_THEN_throws() {
        assertThrows(UnsupportedOperationException.class, () -> new WindowsPlatform.RunAsDecorator().withGroup("foo"));
    }
}
