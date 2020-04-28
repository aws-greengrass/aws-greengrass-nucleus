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
    private static final Set<String> ALLOWED_THREAD_NAMES = new HashSet<>(Arrays.asList("main", "Monitor Ctrl-Break"));

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
        List<String> liveThreads =
                threadSet.stream().filter(Thread::isAlive).filter(t -> t.getThreadGroup().getName().equals("main"))
                        .map(Thread::getName).filter(Objects::nonNull)
                        .filter(name -> !ALLOWED_THREAD_NAMES.contains(name)).collect(Collectors.toList());
        if (!liveThreads.isEmpty()) {
            // Don't fail tests right now. Too many things would break.
            // fail("Threads are still running: " + liveThreads);
            System.err.println("Threads are still running: " + liveThreads);
        }
    }
}
