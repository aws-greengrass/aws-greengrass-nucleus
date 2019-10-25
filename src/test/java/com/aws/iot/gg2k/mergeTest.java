/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.gg2k;

import com.aws.iot.config.Configuration;
import com.aws.iot.dependency.Context;
import org.junit.*;
import static org.junit.Assert.*;

public class mergeTest {
//    boolean seenDocker, seenShell;
//    int seenTickTock = 4;
//    long lastTickTock = 0;
    @Test
    public void testSomeMethod() {
        try {
            Configuration c = new Configuration(new Context());
            c.read(GG2K.class.getResource("config.yaml"));
            Configuration b = new Configuration(new Context()).copyFrom(c);
            assertTrue(c.getRoot().equals(b.getRoot()));
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }


}
