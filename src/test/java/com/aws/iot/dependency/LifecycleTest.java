/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.dependency;

import static com.aws.iot.dependency.State.*;
import org.junit.*;
import javax.inject.*;

public class LifecycleTest {
    static int seq;
    @Test
    public void T1() {
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
        try { Thread.sleep(50); } catch (InterruptedException ex) { }
        Assert.assertNotNull(v);
        Assert.assertNotNull(v.C2);
        Assert.assertSame(v.C2, v.C2.C3.prov.get());
        Assert.assertEquals(State.Finished,v.getState());
        Assert.assertTrue(v.toString(),v.installCalled);
        Assert.assertTrue(v.toString(),v.startupCalled);
        Assert.assertTrue(v.C2.toString(),v.C2.startupCalled);
        Assert.assertEquals(State.Finished,v.getState());
        c.shutdown();
        try { Thread.sleep(50); } catch (InterruptedException ex) { }
        Assert.assertTrue(v.toString(),v.shutdownCalled);
        Assert.assertTrue(v.C2.toString(),v.C2.shutdownCalled);
        Assert.assertEquals(State.Shutdown,v.getState());
        Assert.assertNotNull("non-lifecycle", v.C2.C3);
        Assert.assertSame("non-lifecycle-loop", v.C2.C3, v.C2.C3.self);
        Assert.assertSame("non-lifecycle-parent-ref", v.C2, v.C2.C3.parent);
        Assert.assertEquals(42,c.get(Foo.class).what());
    }
    public static class c1 extends Lifecycle {
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
            Assert.assertEquals(State.Running,C2.getState());
            System.out.println("Startup "+this);
            super.startup();
        }
        @Override public void shutdown() { 
            shutdownCalled = true;
            System.out.println("Shutdown "+this);
            super.shutdown();
        }
        final String id = "c1/"+ ++seq;
        @Override public String toString() { return id; }
        { System.out.println("Creating  "+this); }
    }
    public static class c2 extends Lifecycle {
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
            super.startup();
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
