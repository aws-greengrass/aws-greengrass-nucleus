/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.dependency;

import com.aws.iot.config.Topics;
import static com.aws.iot.dependency.State.*;
import com.aws.iot.gg2k.GGService;
import java.util.concurrent.*;
import org.junit.*;
import javax.inject.*;

public class LifecycleTest {
    static int seq;
    static CountDownLatch cd;
    @Test
    public void T1() {
        cd = new CountDownLatch(2);
        Context c = new Context();
        java.util.concurrent.ScheduledThreadPoolExecutor ses = new java.util.concurrent.ScheduledThreadPoolExecutor(2);
        c.put(java.util.concurrent.ScheduledThreadPoolExecutor.class, ses);
        c.put(java.util.concurrent.ScheduledExecutorService.class, ses);
        c.put(java.util.concurrent.Executor.class, ses);
        c.put(java.util.concurrent.ExecutorService.class, ses);
        c.put(java.util.concurrent.ThreadPoolExecutor.class, ses);
        c1 v = c.get(c1.class);
        c.setAllStates(Installing);
        c.setAllStates(AwaitingStartup);
        try {
            if(!cd.await(1, TimeUnit.SECONDS))
                Assert.fail("Startup timed out");
        } catch (InterruptedException ex) {
            ex.printStackTrace(System.out);
            Assert.fail("Startup interrupted out");
        }
        Assert.assertNotNull(v);
        Assert.assertNotNull(v.C2);
        Assert.assertSame(v.C2, v.C2.C3.prov.get());
            Assert.assertTrue(v.getState().isFunctioningProperly());
        Assert.assertTrue(v.toString(),v.installCalled);
        Assert.assertTrue(v.toString(),v.startupCalled);
        Assert.assertTrue(v.C2.toString(),v.C2.startupCalled);
            Assert.assertTrue(v.getState().isFunctioningProperly());
        c.shutdown();
        try { Thread.sleep(50); } catch (InterruptedException ex) { }
        Assert.assertTrue(v.toString(),v.shutdownCalled);
        Assert.assertTrue(v.C2.toString(),v.C2.shutdownCalled);
        System.out.println("XYXXY: "+v.getState());
            Assert.assertEquals(Shutdown, v.getState());
        Assert.assertNotNull("non-lifecycle", v.C2.C3);
        Assert.assertSame("non-lifecycle-loop", v.C2.C3, v.C2.C3.self);
        Assert.assertSame("non-lifecycle-parent-ref", v.C2, v.C2.C3.parent);
        Assert.assertEquals(42,c.get(Foo.class).what());
    }
    public static class c1 extends GGService {
        public c1() {
            super(Topics.errorNode("c1","testing"));
        }
        @Inject public c2 C2;
        public boolean shutdownCalled, startupCalled, installCalled;
        @Override public void install() {
            installCalled = true;
            System.out.println("Startup "+this);
            super.install();
        }
        @Override public void startup() {
            startupCalled = true;
            // Depen dependencies must be started first
            Assert.assertTrue(C2.getState().isFunctioningProperly());
            System.out.println("Startup "+this);
            super.startup();
        }
        @Override public void shutdown() { 
            shutdownCalled = true;
            System.out.println("Shutdown "+this);
            super.shutdown();
            cd.countDown();
        }
        final String id = "c1/"+ ++seq;
        @Override public String toString() { return id; }
        { System.out.println("Creating  "+this); }
    }
    public static class c2 extends GGService {
        public c2() {
            super(Topics.errorNode("c2","testing"));
        }
        @Inject c3 C3;
//        @Inject @StartWhen(New) c1 parent;
        public boolean shutdownCalled, startupCalled;
        final String id = "c2/"+ ++seq;
        @Override public String toString() { return id; }
        { System.out.println("Creating  "+this); }
        @Override public void startup() {
            Assert.assertNotNull(C3);
            startupCalled = true;
            System.out.println("Startup "+this);
            System.out.println("  C3="+C3);
            super.startup();
        }
        @Override public void shutdown() { 
            shutdownCalled = true;
            System.out.println("Shutdown "+this);
            super.shutdown();
            cd.countDown();
        }
    }
    public static class c3 {
        @Inject c3 self;
        @Inject c2 parent;
        @Inject Provider<c2> prov;
        @Override public String toString() { return super.toString()+"::"+parent; }
        { System.out.println("Hello from c3: "+this); }
    }
    public interface Foo {
        public int what();
        public static class Default implements Foo {
            @Override public int what() { return 42; }
        }
    }
    
}
