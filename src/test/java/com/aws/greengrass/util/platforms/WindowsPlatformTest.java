/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
public class WindowsPlatformTest {

    @Test
    public void GIVEN_command_WHEN_decorate_THEN_is_decorated() {
        assertThat(new WindowsPlatform.CmdDecorator()
                        .decorate("echo", "hello"),
                is(arrayContaining("cmd.exe", "/C", "echo", "hello")));
    }
}
