/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.util.Pair;
import software.amazon.awssdk.crt.io.SocketOptions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public final class TestUtils {
    private TestUtils() {
    }

    /**
     * Get the configuration as a POJO for the nucleus component in the kernel. This will also assert that that
     * config is present.
     *
     * @param kernel a kernel
     * @return the nucleus config.
     */
    public static Map<String, Object> getNucleusConfig(Kernel kernel) {
        Optional<GreengrassService> nucleus =
                kernel.getMain().getDependencies().keySet().stream().filter(s ->
                        DEFAULT_NUCLEUS_COMPONENT_NAME.equalsIgnoreCase(s.getServiceName()))
                        .findFirst();
        assertTrue(nucleus.isPresent(), "no nucleus config available");
        return nucleus.get().getConfig().toPOJO();
    }

    /**
     * Create a runnable that when run, will wait for a latch to countdown ensuring that a state change for the
     * service has occurred. The listener is added immediately. The latch waits when the runnable runs.
     *
     * @param kernel a kernel to monitor
     * @param serviceName the service to watch
     * @param timeoutSeconds the time to wait for the state change
     * @param state the state to watch for
     * @return a runnable that can be used for asserting that a state has been entered.
     */
    public static Runnable createServiceStateChangeWaiter(Kernel kernel, String serviceName, long timeoutSeconds,
            State state) {
        return createServiceStateChangeWaiter(kernel, serviceName, timeoutSeconds, state, null);
    }

    /**
     * Create a runnable that when run, will wait for a latch to countdown ensuring that a state change for the
     * service has occurred. The listener is added immediately. The latch waits when the runnable runs.
     *
     * @param kernel a kernel to monitor
     * @param serviceName the service to watch
     * @param timeoutSeconds the time to wait for the state change
     * @param state the state to watch for
     * @param prevState the previous state to transition from
     * @return a runnable that can be used for asserting that a state has been entered.
     */
    public static Runnable createServiceStateChangeWaiter(Kernel kernel, String serviceName, long timeoutSeconds,
            State state, State prevState) {
        CountDownLatch latch = new CountDownLatch(1);
        GlobalStateChangeListener l = (service, oldState, newState) -> {
            if (service.getName().equals(serviceName) && newState.equals(state) && prevState == null ||
                    oldState.equals(prevState)) {
                latch.countDown();
            }
        };
        kernel.getContext().addGlobalStateChangeListener(l);

        return () -> {
            try {
                assertThat(String.format("%s in state %s", serviceName, state.getName()),
                        latch.await(timeoutSeconds, TimeUnit.SECONDS), is(true));
            } catch (InterruptedException e) {
                fail(e);
            } finally {
                kernel.getContext().removeGlobalStateChangeListener(l);
            }
        };
    }
    /**
     * Create an AutoCloseable object so that log listeners can be added and auto removed from the
     * {@link com.aws.greengrass.logging.impl.Slf4jLogAdapter} via try-with-resources block.
     *
     * @param c the log consumer
     * @return the wrapped instance.
     */
    public static AutoCloseable createCloseableLogListener(Consumer<GreengrassLogMessage> c) {
        Slf4jLogAdapter.addGlobalListener(c);
        return new AutoCloseable() {
            @Override
            public void close() throws Exception {
                Slf4jLogAdapter.removeGlobalListener(c);
            }
        };
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

    public static void validateGenerateErrorReport(Throwable e, List<String> expectedErrorStack,
                                                   List<String> expectedErrorTypes) {
        Pair<List<String>, List<String>> errorReport =
                DeploymentErrorCodeUtils.generateErrorReportFromExceptionStack(e);
        assertEquals(errorReport.getLeft(), expectedErrorStack);
        assertThat(errorReport.getRight(), containsInAnyOrder(expectedErrorTypes.toArray()));
    }
}
