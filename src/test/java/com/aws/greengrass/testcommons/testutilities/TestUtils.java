/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.util.Pair;
import software.amazon.awssdk.crt.io.SocketOptions;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public final class TestUtils {
    private TestUtils() {
    }

    /**
     * Wraps a given biconsumer function so that once it is called, the completable future can complete with the
     * exception, or with a success.
     *
     * @param bi
     * @return
     */
    public static <A, B> Pair<CompletableFuture<Void>, BiConsumer<A, B>> asyncAssertOnBiConsumer(BiConsumer<A, B> bi) {
        return asyncAssertOnBiConsumer(bi, 1);
    }


    /**
     * Wraps a given biconsumer function so that once it is called, the completable future can
     * complete with the exception, or with a success.
     *
     * @param bi
     * @param numCalls number of expected calls
     */
    public static <A, B> Pair<CompletableFuture<Void>, BiConsumer<A, B>> asyncAssertOnBiConsumer(BiConsumer<A, B> bi,
                                                                                                 int numCalls) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger(0);

        return new Pair<>(f, (a, b) -> {
            try {
                int callsSoFar = calls.incrementAndGet();
                bi.accept(a, b);

                if (callsSoFar == numCalls) {
                    f.complete(null);
                }
            } catch (Throwable ex) {
                f.completeExceptionally(ex);
            }
        });
    }

    public static <A> Pair<CompletableFuture<Void>, Consumer<A>> asyncAssertOnConsumer(Consumer<A> c) {
        return asyncAssertOnConsumer(c, 1);
    }

    /**
     * Creates a test utility wrapping a given Consumer and returning a new Consumer and Future. Use the Future to
     * validate that the Consumer is called numCalls times without any exceptions.
     *
     * @param c        Consumer to wrap
     * @param numCalls number of expected calls. -1 will ignore the number of expected calls
     */
    public static <A> Pair<CompletableFuture<Void>, Consumer<A>> asyncAssertOnConsumer(Consumer<A> c, int numCalls) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();

        return new Pair<>(f, (a) -> {
            try {
                int callsSoFar = calls.incrementAndGet();
                c.accept(a);

                if (callsSoFar == numCalls || numCalls < 0) {
                    f.complete(null);
                } else if (callsSoFar > numCalls) {
                    f.obtrudeException(
                            new Exception("Too many invocations! (" + callsSoFar + "), expected " + numCalls));
                }
            } catch (Throwable ex) {
                f.obtrudeException(ex);
            }
        });
    }

    public static ExecutorService synchronousExecutorService() {
        return new AbstractExecutorService() {

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return null;
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return false;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    public static SocketOptions getSocketOptionsForIPC() {
        SocketOptions socketOptions = new SocketOptions();
        socketOptions.connectTimeoutMs = 3000;
        socketOptions.domain = SocketOptions.SocketDomain.LOCAL;
        socketOptions.type = SocketOptions.SocketType.STREAM;
        return socketOptions;
    }
}
