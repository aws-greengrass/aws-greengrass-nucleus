/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SuppressWarnings("PMD.SystemPrintln")
public class ThreadProtector implements AfterAllCallback, BeforeAllCallback {
    private static final Set<String> ALLOWED_THREAD_NAMES = new HashSet<>(Arrays.asList(
            "main",
            "Monitor Ctrl-Break",
            "surefire-forkedjvm-command-thread",
            "surefire-forkedjvm-stream-flusher",
            "blocked-thread-catcher",
            "junit-jupiter-timeout-watcher",
            "idle-connection-reaper",
            "java-sdk-http-connection-reaper"));
    private Thread t;

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (t != null) {
            t.interrupt();
        }

        List<Thread> liveThreads = getThreads();
        if (!liveThreads.isEmpty()) {
            // Wait, then try again and see if they're still running
            Thread.sleep(200);
            liveThreads = getThreads();
            if (!liveThreads.isEmpty()) {
                System.err.println("Threads are still running: " + liveThreads);
//                fail("Threads are still running: " + liveThreads);
            }
        }
    }

    private List<Thread> getThreads() {
        Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
        return threadSet.stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getThreadGroup() != null && "main".equals(t.getThreadGroup().getName()))
                .filter(t -> Objects.nonNull(t.getName()))
                .filter(t -> !ALLOWED_THREAD_NAMES.contains(t.getName()))
                // This executor is used by Netty and it will shutdown, it just shutsdown a bit slowly, so
                // that's why we will ignore it here
                .filter(t -> !t.getName().contains("globalEventExecutor"))
                .collect(Collectors.toList());
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        t = new Thread(() -> {
            Thread.currentThread().setName("blocked-thread-catcher");
            // Initial sleep time is 5 minutes (default test timeout). After the first 5 minutes, we then
            // dump threads every 1 minute.
            long sleepTime = 5L;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.MINUTES.sleep(sleepTime);
                    sleepTime = 1L;
                } catch (InterruptedException e) {
                    break;
                }

                System.err.println("Checking for blocked threads");
                System.err.flush();
                long[] deadLocked = threadBean.findDeadlockedThreads();
                long[] deadLockedMon = threadBean.findMonitorDeadlockedThreads();
                if (deadLocked != null && deadLocked.length > 0) {
                    for (ThreadInfo ti : threadBean.getThreadInfo(deadLocked, true, true)) {
                        System.err.println(ti);
                        System.err.flush();
                    }
                }

                if (deadLockedMon != null && deadLockedMon.length > 0) {
                    for (ThreadInfo ti : threadBean.getThreadInfo(deadLockedMon, true, true)) {
                        System.err.println(ti);
                        System.err.flush();
                    }
                }

                if ((deadLocked == null || deadLocked.length == 0) &&
                        (deadLockedMon == null || deadLockedMon.length == 0)) {
                    System.err.println("No blocked threads found? Dumping all threads");
                    System.err.flush();
                    for (ThreadInfo ti : threadBean.dumpAllThreads(true, true)) {
                        System.err.println(ti);
                        System.err.flush();
                    }
                }
            }
        });
        t.start();
    }
}
