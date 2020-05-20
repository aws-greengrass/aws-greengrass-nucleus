/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.testcommons.testutilities;

import com.aws.iot.evergreen.util.Pair;
import org.jetbrains.annotations.NotNull;

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
     * Wraps a given biconsumer function so that once it is called, the completable future can
     * complete with the exception, or with a success.
     *
     * @param bi
     * @return
     */
    public static <A, B> Pair<CompletableFuture<Void>, BiConsumer<A, B>> asyncAssertOnBiConsumer(BiConsumer<A, B> bi) {
        CompletableFuture<Void> f = new CompletableFuture<>();

        return new Pair<>(f, (a, b) -> {
            try {
                bi.accept(a, b);
                f.complete(null);
            } catch (Throwable ex) {
                f.completeExceptionally(ex);
            }
        });
    }

    public static <A> Pair<CompletableFuture<Void>, Consumer<A>> asyncAssertOnConsumer(Consumer<A> c) {
        return asyncAssertOnConsumer(c, 1);
    }

    /**
     * Creates a test utility wrapping a given Consumer and returning a new Consumer and Future.
     * Use the Future to validate that the Consumer is called numCalls times without any exceptions.
     *
     * @param c Consumer to wrap
     * @param numCalls number of expected calls
     */
    public static <A> Pair<CompletableFuture<Void>, Consumer<A>> asyncAssertOnConsumer(Consumer<A> c, int numCalls) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();

        return new Pair<>(f, (a) -> {
            try {
                int callsSoFar = calls.incrementAndGet();
                c.accept(a);

                if (callsSoFar == numCalls) {
                    f.complete(null);
                }
            } catch (Throwable ex) {
                f.completeExceptionally(ex);
            }
        });
    }

    public static ExecutorService synchronousExecutorService() {
        return new AbstractExecutorService() {

            @Override
            public void shutdown() {
            }

            @NotNull
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
            public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
                return false;
            }

            @Override
            public void execute(@NotNull Runnable command) {
                command.run();
            }
        };
    }
}
