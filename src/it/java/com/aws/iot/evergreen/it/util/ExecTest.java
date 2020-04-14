/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.it.util;

import com.aws.iot.evergreen.util.Exec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecTest {
    @Test
    void test() throws InterruptedException {
        if (Exec.isWindows) {
            return;
        }
        String s = Exec.cmd("pwd");
        //        System.out.println("pwd: "+s);
        assertFalse(s.contains("\n"));
        assertTrue(s.startsWith("/"));
        assertEquals(s, Exec.sh("pwd"));
        String s2 = Exec.sh("ifconfig -a;echo Hello");
        //        System.out.println(s2);
        assertTrue(s2.contains("Hello"));
    }

    @Test
    void test2() throws InterruptedException {
        //        System.out.println(Exec.sh("printenv;java --version"));
        //        assertFalse(Exec.successful("java --version|egrep -i -q '(jdk|jre) *17\\.'"));
        //        assertTrue(Exec.successful("java --version|egrep -i -q '(jdk|jre) *11\\.'"));
        assertFalse(Exec.successful(false, "echo openjdk 11.0|egrep -i -q '(jdk|jre) *18\\.'"));
        assertTrue(new Exec().withShell("echo openjdk 11.0|egrep -i -q '(jdk|jre) *11\\.'").withDumpOut()
                .successful(false));
    }

    @Test
    void test3() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<String> o = new ArrayList<>();
        List<String> e = new ArrayList<>();

        new Exec().withShell("pwd").withOut(str -> o.add(str.toString())).withErr(str -> e.add(str.toString()))
                .background(exc -> done.countDown());
        assertTrue(done.await(10, TimeUnit.SECONDS));
        //        System.out.println("O: "+deepToString(o));
        //        System.out.println("E: "+deepToString(e));
        assertEquals(0, e.size());
        assertEquals(1, o.size());
        assertTrue(o.get(0).startsWith("/"));
    }

}
