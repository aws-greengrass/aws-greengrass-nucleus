/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.testcommons.testutilities;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings("PMD.SystemPrintln")
public class ThreadProtector implements AfterAllCallback {
    private static final Set<String> ALLOWED_THREAD_NAMES = new HashSet<>(Arrays.asList(
            "main",
            "Monitor Ctrl-Break",
            "surefire-forkedjvm-command-thread",
            "junit-jupiter-timeout-watcher",
            "idle-connection-reaper",
            "java-sdk-http-connection-reaper"));

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        List<Thread> liveThreads = getThreads();
        if (!liveThreads.isEmpty()) {
            System.err.println("Threads are still running: " + liveThreads);

            /*
            // Wait, then try again and see if they're still running
            Thread.sleep(2000);
            liveThreads = getThreads();
            if (!liveThreads.isEmpty()) {
                fail("Threads are still running: " + liveThreads);
            }
             */
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
}
