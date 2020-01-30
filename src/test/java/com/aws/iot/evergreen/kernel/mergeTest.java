/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.dependency.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class mergeTest {
    //    boolean seenDocker, seenShell;
    //    int seenTickTock = 4;
    //    long lastTickTock = 0;
    @Test
    public void testSomeMethod() {
        try {
            Configuration c = new Configuration(new Context());
            c.read(Kernel.class.getResource("config.yaml"), false);
            Configuration b = new Configuration(new Context()).copyFrom(c);
            assertEquals(c.getRoot(), b.getRoot());
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }


}
