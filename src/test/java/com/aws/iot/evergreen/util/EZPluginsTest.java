/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import com.aws.iot.evergreen.dependency.EZPlugins;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class EZPluginsTest {
    int hits;
    @Test
    public void testMatch() {
        System.out.println(Exec.sh("pwd"));
        EZPlugins pl = new EZPlugins(Utils.homePath(".pluginsTest"));
        pl.implementing(Foo.class, f -> {
            System.out.println(f.getCanonicalName());
            try {
                hits++;
                f.newInstance().p("Hello");
            } catch (Throwable ex) {
                cause(ex).printStackTrace(System.out);
                fail(ex.toString());
            }
        });
        try {
            pl.loadCache();
        } catch (IOException ex) {
            cause(ex).printStackTrace(System.out);
            fail(ex.toString());
        }
        assertEquals(2, hits);
    }

    private interface Foo {
        public void p(String s);
    }

    public static class A implements Foo {
        @Override
        public void p(String s) {
            System.out.println("A:" + s);
        }
    }

    public static class B implements Foo {
        @Override
        public void p(String s) {
            System.out.println("B:" + s);
        }

    }
    private static Throwable cause(Throwable t) {
        Throwable c = t.getCause();
        return c==null ? t : cause(c);
    }
}
