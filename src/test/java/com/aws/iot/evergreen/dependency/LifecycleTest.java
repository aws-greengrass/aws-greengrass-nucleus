/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.EvergreenService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("Integration")
@Disabled("Will be written. Relying on Kernel Test for now.")
public class LifecycleTest {
    static int seq;
    static CountDownLatch cd;

    @Test
    public void T1() {
        cd = new CountDownLatch(2);
        Context context = new Context();
        java.util.concurrent.ScheduledThreadPoolExecutor ses = new java.util.concurrent.ScheduledThreadPoolExecutor(2);
        context.put(java.util.concurrent.ScheduledThreadPoolExecutor.class, ses);
        context.put(java.util.concurrent.ScheduledExecutorService.class, ses);
        context.put(java.util.concurrent.Executor.class, ses);
        context.put(java.util.concurrent.ExecutorService.class, ses);
        context.put(java.util.concurrent.ThreadPoolExecutor.class, ses);
        c1 v = context.get(c1.class);
        context.addGlobalStateChangeListener((service, prevState, activeState) -> System.out.println(service.getName() + ": " + prevState + " " +
                "=> " + activeState));
        context.addDesiredStatesForAll(State.RUNNING);
        try {
            if (!cd.await(1, TimeUnit.SECONDS)) {
                fail("Startup timed out");
            }
        } catch (InterruptedException ex) {
            ex.printStackTrace(System.out);
            fail("Startup interrupted out");
        }
        assertNotNull(v);
        assertNotNull(v.C2);
        assertSame(v.C2, v.C2.C3.prov.get());
        assertTrue(v.getActiveState().isFunctioningProperly());
        assertTrue(v.installCalled, v.toString());
        assertTrue(v.startupCalled, v.toString());
        assertTrue(v.C2.startupCalled, v.C2.toString());
        assertTrue(v.getActiveState().isFunctioningProperly());
        context.shutdown();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
        }
        assertTrue(v.shutdownCalled, v.toString());
        assertTrue(v.C2.shutdownCalled, v.C2.toString());
        System.out.println("XYXXY: " + v.getActiveState());
        assertEquals(State.FINISHED, v.getActiveState());
        assertNotNull(v.C2.C3, "non-lifecycle");
        assertSame(v.C2.C3, v.C2.C3.self, "non-lifecycle-loop");
        assertSame(v.C2, v.C2.C3.parent, "non-lifecycle-parent-ref");
        assertEquals(42, context.get(Foo.class).what());
    }

    public interface Foo {
        int what();

        class Default implements Foo {
            @Override
            public int what() {
                return 42;
            }
        }
    }

    public static class c2 extends EvergreenService {
        final String id = "c2/" + ++seq;
        //        @Inject @StartWhen(NEW) c1 parent;
        public boolean shutdownCalled, startupCalled;
        @Inject
        c3 C3;

        {
            System.out.println("Creating  " + this);
        }

        @Inject
        public c2(Context context) {
            super(Topics.errorNode(context, "c2", "testing"));
        }

        @Override
        public String toString() {
            return id;
        }

        @Override
        public void startup() {
            System.out.println("Startup " + this);
            assertNotNull(C3);
            startupCalled = true;
            System.out.println("  C3=" + C3);
            super.startup();
        }

        @Override
        public void shutdown() throws IOException {
            shutdownCalled = true;
            System.out.println("Shutdown " + this);
            super.shutdown();
            cd.countDown();
        }
    }

    public static class c3 {
        @Inject
        c3 self;
        @Inject
        c2 parent;
        @Inject
        Provider<c2> prov;

        {
            System.out.println("Hello from c3: " + this);
        }

        @Override
        public String toString() {
            return super.toString() + "::" + parent;
        }
    }

    public class c1 extends EvergreenService {
        final String id = "c1/" + ++seq;
        @Inject
        public c2 C2;
        public boolean shutdownCalled, startupCalled, installCalled;

        {
            System.out.println("Creating  " + this);
        }

        @Inject
        public c1(Context context) {
            super(Topics.errorNode(context, "c1", "testing"));
        }

        @Override
        public void install() {
            installCalled = true;
            System.out.println("Invoked install " + this);
            super.install();
        }

        @Override
        public void startup() {
            startupCalled = true;
            // Depen dependencies must be started first
            assertTrue(C2.getActiveState().isFunctioningProperly());
            System.out.println("Startup " + this);
            super.startup();
        }

        @Override
        public void shutdown() throws IOException {
            shutdownCalled = true;
            System.out.println("Shutdown " + this);
            super.shutdown();
            cd.countDown();
        }

        @Override
        public String toString() {
            return id;
        }
    }

}
