/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen.util;

import java.io.*;
import org.junit.*;
import static org.junit.Assert.*;


public class JsonEndpointTest {
    int expected;
    boolean received;
    @Test
    public void test1() throws IOException, InterruptedException {
        POJOStreamEndpoint.Dispatcher d = new POJOStreamEndpoint.Dispatcher();
        POJOStreamEndpoint.Server s = POJOStreamEndpoint.startServer(34242, d);
        POJOStreamEndpoint e = POJOStreamEndpoint.startConnection(null, 34242, d, null);
        e.sendMap(o->gotIt(o),"op","ping");
        assertTrue(waitForIt());
        s.stop();
    }
    private synchronized void gotIt(Object v) {
        received = true;
        System.out.println("Got it!  "+v);
        notifyAll();
    }
    private synchronized boolean waitForIt() {
        final long timeout = System.currentTimeMillis()+15000;
        while(!received)
            try {
                long waitTime = timeout - System.currentTimeMillis();
                if(waitTime<=0) return false;
                wait(waitTime);
            } catch (InterruptedException ex) { }
        received = false;
        return true;
    }
}
