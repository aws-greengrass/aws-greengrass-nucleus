/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
@ExtendWith(GGExtension.class)
class EZPluginsTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    int hits;

    private static Throwable cause(Throwable t) {
        Throwable c = t.getCause();
        return c == null ? t : cause(c);
    }

    @AfterEach
    void after() {
        executor.shutdownNow();
    }

    @Test
    void testMatch() throws Exception {
        try (EZPlugins pl = new EZPlugins(executor, Utils.homePath(".pluginsTest"))) {
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
    }

    private interface Foo {
        void p(String s);
    }

    static class A implements Foo {
        @Override
        public void p(String s) {
            System.out.println("A:" + s);
        }
    }

    static class B implements Foo {
        @Override
        public void p(String s) {
            System.out.println("B:" + s);
        }

    }
}
