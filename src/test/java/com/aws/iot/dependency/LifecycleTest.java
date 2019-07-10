/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.dependency;

import com.aws.iot.dependency.Context.Dependency;
import com.aws.iot.dependency.Context.StartWhen;
import static com.aws.iot.dependency.Lifecycle.State.*;
import org.junit.*;

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
//        System.out.println(v);
        Assert.assertNotNull(v.C2);
        Assert.assertEquals(Lifecycle.State.Running,v.getState());
        Assert.assertTrue(v.toString(),v.installCalled);
        Assert.assertTrue(v.toString(),v.startupCalled);
        Assert.assertTrue(v.C2.toString(),v.C2.startupCalled);
        Assert.assertEquals(Lifecycle.State.Running,v.getState());
        c.shutdown();
        Assert.assertTrue(v.toString(),v.shutdownCalled);
        Assert.assertTrue(v.C2.toString(),v.C2.shutdownCalled);
        Assert.assertEquals(Lifecycle.State.Shutdown,v.getState());
        Assert.assertNotNull("non-lifecycle", v.C2.C3);
        Assert.assertEquals("non-lifecycle-loop", v.C2.C3, v.C2.C3.self);
        Assert.assertEquals("non-lifecycle-parent-ref", v.C2, v.C2.C3.parent);
        Assert.assertEquals(42,c.get(Foo.class).what());
    }
    public static class c1 extends Lifecycle {
        @Dependency public c2 C2;
        public boolean shutdownCalled, startupCalled, installCalled;
        @Override public void install() {
            installCalled = true;
            System.out.println("Startup "+this);
        }
        @Override public void startup() {
            startupCalled = true;
            // Depen dependencies must be started first
            Assert.assertEquals(State.Running,C2.getState());
            System.out.println("Install "+this);
        }
        @Override public void shutdown() { 
            shutdownCalled = true;
            System.out.println("Shutdown "+this);
        }
        final String id = "c1/"+ ++seq;
        @Override public String toString() { return id; }
        { System.out.println("Creating  "+this); }
    }
    public static class c2 extends Lifecycle {
        @Dependency c3 C3;
        @Dependency @StartWhen(New) c1 parent;
        public boolean shutdownCalled, startupCalled;
        final String id = "c2/"+ ++seq;
        @Override public String toString() { return id; }
        { System.out.println("Creating  "+this); }
        @Override public void startup() {
            Assert.assertNotNull(C3);
            startupCalled = true;
            System.out.println("Startup "+this);
            System.out.println("  C3="+C3);
        }
        @Override public void shutdown() { 
            shutdownCalled = true;
            System.out.println("Shutdown "+this);
        }
    }
    public static class c3 {
        @Dependency c3 self;
        @Dependency c2 parent;
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
