/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AllArgsConstructor;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class MultiInputStreamReader implements Closeable {
    private static final Logger logger = LogManager.getLogger(MultiInputStreamReader.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Map<Process, Pair<ProcessStreamData, ProcessStreamData>> processes = new ConcurrentHashMap<>();
    private Future<?> task;

    @Override
    public synchronized void close() {
        if (task != null) {
            task.cancel(true);
        }
    }

    /**
     * Add a process to read the stdout and stderr using consumers.
     *
     * @param p              process
     * @param stdoutConsumer consumer to call on each line of stdout
     * @param stderrConsumer consumer to call on each line of stderr
     * @param whenDone       method to call when the stream has ended
     */
    public void addProcess(Process p, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer,
                           Runnable whenDone) {
        processes.put(p, new Pair<>(new ProcessStreamData(stdoutConsumer,
                new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)), whenDone),
                new ProcessStreamData(stderrConsumer,
                        new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)),
                        whenDone)));
        run();
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private synchronized void run() {
        if (task != null) {
            return;
        }
        task = executor.submit(() -> {
            while (true) {
                try {
                    Thread.sleep(250); // Sleep a bit so we don't use 100% of CPU
                    for (Map.Entry<Process, Pair<ProcessStreamData, ProcessStreamData>> e : processes.entrySet()) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }

                        try {
                            read(e.getKey(), e.getValue().getLeft(), e.getValue().getRight());
                        } catch (IOException ignored) {
                        }

                        try {
                            read(e.getKey(), e.getValue().getRight(), e.getValue().getLeft());
                        } catch (IOException ignored) {
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    logger.atError().cause(t).log();
                }
            }
        });
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void read(Process proc, ProcessStreamData d, ProcessStreamData other) throws IOException {
        if (d.reader == null) {
            return;
        }

        // ready() doesn't block so it is safe to call, but it will return false
        // if we're at the end of the stream and nothing else is coming.
        // There's no way to tell that we're at the end of the stream
        // without the *possibility* of blocking, so we try to be a bit smart here.
        // We only call the blocking "read()" call when the process is not alive anymore
        // by which point we know that the streams are closed, and for unix at least, the JVM
        // replaces the FileInputStream with a ByteArrayInputStream which definitely won't block on "read()".
        if (d.reader.ready() || !proc.isAlive() && shouldReadyAnyway(d.reader)) {
            String line = d.reader.readLine();
            if (line == null) {
                d.reader.close();
                d.reader = null;

                // Only remove the stream and consider it closed once
                // both streams (stdout and stderr) have ended.
                if (other.reader == null) {
                    removeStream(proc);
                }
            } else {
                d.consumer.accept(line);
            }
        }
    }

    private boolean shouldReadyAnyway(BufferedReader reader) throws IOException {
        if (reader.markSupported()) {
            reader.mark(1);
            // We try hard, but in theory this *might* block, but that's pretty unlikely since the process
            // is no longer living.
            boolean c = reader.read() < 0;
            reader.reset();
            return c;
        }
        return false;
    }

    private void removeStream(Process i) throws IOException {
        Pair<ProcessStreamData, ProcessStreamData> d = processes.remove(i);
        if (d.getRight().reader != null) {
            d.getRight().reader.close();
        }
        if (d.getLeft().reader != null) {
            d.getLeft().reader.close();
        }
        if (d.getLeft().whenDone != null) {
            d.getLeft().whenDone.run();
        }
    }

    @AllArgsConstructor
    private static class ProcessStreamData {
        Consumer<String> consumer;
        BufferedReader reader;
        Runnable whenDone;
    }
}
