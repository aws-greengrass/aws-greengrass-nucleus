/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

@SuppressWarnings({"PMD.CloseResource", "PMD.NonStaticInitializer"})
@ExtendWith(EGExtension.class)
public class LifecycleTest {
    static int seq;
    static CountDownLatch cd;
    private Context context;

    @BeforeEach
    void beforeEach() {
        context = new Context();
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(2);
        ExecutorService cachedPool = Executors.newCachedThreadPool();
        context.put(ScheduledThreadPoolExecutor.class, ses);
        context.put(ScheduledExecutorService.class, ses);
        context.put(Executor.class, cachedPool);
        context.put(ExecutorService.class, cachedPool);
        context.put(ThreadPoolExecutor.class, ses);
        context.put(Kernel.class, mock(Kernel.class));
    }

    @AfterEach
    void afterEach() throws IOException {
        context.get(ScheduledThreadPoolExecutor.class).shutdownNow();
        context.get(ExecutorService.class).shutdownNow();
        context.close();
    }

    @Test
    public void T1() {
        cd = new CountDownLatch(2);

        c1 v = context.get(c1.class);
        context.get(c1.class).requestStart();
        context.get(C2.class).requestStart();
        try {
            if (!cd.await(5, TimeUnit.SECONDS)) {
                fail("Startup timed out");
            }
        } catch (InterruptedException ex) {
            ex.printStackTrace(System.out);
            fail("Startup interrupted out");
        }

        cd = new CountDownLatch(2);
        context.get(c1.class).requestStop();
        context.get(C2.class).requestStop();
        try {
            if (!cd.await(1, TimeUnit.SECONDS)) {
                fail("Stop timed out");
            }
        } catch (InterruptedException ex) {
            ex.printStackTrace(System.out);
            fail("Startup interrupted out");
        }
        assertNotNull(v);
        assertNotNull(v.c2);
        assertSame(v.c2, v.c2.c3.prov.get());
        assertTrue(v.getState().isFunctioningProperly(), "c1:" + v.getState().toString());
        assertTrue(v.installCalled, v.toString());
        assertTrue(v.startupCalled, v.toString());
        assertTrue(v.c2.startupCalled, v.c2.toString());
        assertTrue(v.getState().isFunctioningProperly());
        context.shutdown();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
        }
        assertTrue(v.shutdownCalled, v.toString());
        assertTrue(v.c2.shutdownCalled, v.c2.toString());
        System.out.println("XYXXY: " + v.getState());
        assertEquals(State.FINISHED, v.getState());
        assertNotNull(v.c2.c3, "non-lifecycle");
        assertSame(v.c2.c3, v.c2.c3.self, "non-lifecycle-loop");
        assertSame(v.c2, v.c2.c3.parent, "non-lifecycle-parent-ref");
    }

    public static class C2 extends EvergreenService {
        final String id = "c2/" + ++seq;
        public boolean shutdownCalled;
        public boolean startupCalled;
        @Inject
        LifecycleTest.C3 c3;

        {
            System.out.println("Creating  " + this);
        }

        @Inject
        public C2(Context context) {
            super(Topics.errorNode(context, "c2", "testing"));
        }

        @Override
        public String toString() {
            return id;
        }

        @Override
        public void startup() throws InterruptedException {
            System.out.println("Startup " + this);
            assertNotNull(c3);
            startupCalled = true;
            System.out.println("  c3=" + c3);
            cd.countDown();
            super.startup();
        }

        @Override
        public void shutdown() throws InterruptedException {
            shutdownCalled = true;
            System.out.println("Shutdown " + this);
            super.shutdown();
            cd.countDown();
        }
    }

    public static class C3 {
        @Inject
        C3 self;
        @Inject
        C2 parent;
        @Inject
        Provider<C2> prov;

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
        public LifecycleTest.C2 c2;
        public boolean shutdownCalled;
        public boolean startupCalled;
        public boolean installCalled;

        {
            System.out.println("Creating  " + this);
        }

        @Inject
        public c1(Context context) {
            super(Topics.errorNode(context, "c1", "testing"));
        }

        @Override
        public void install() throws InterruptedException {
            installCalled = true;
            System.out.println("Invoked install " + this);
            super.install();
            System.out.println(dependencies);
        }

        @Override
        public void startup() throws InterruptedException {
            startupCalled = true;
            super.startup();
            System.out.println("Startup called " + this);
            //dependencies must be started first
            assertTrue(c2.getState().isFunctioningProperly());
            System.out.println("Startup " + this);
            cd.countDown();
        }

        @Override
        public void shutdown() throws InterruptedException {
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
