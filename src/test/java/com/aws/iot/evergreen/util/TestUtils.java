package com.aws.iot.evergreen.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TestUtils {
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
        CompletableFuture<Void> f = new CompletableFuture<>();

        return new Pair<>(f, (a) -> {
            try {
                c.accept(a);
                f.complete(null);
            } catch (Throwable ex) {
                f.completeExceptionally(ex);
            }
        });
    }
}
