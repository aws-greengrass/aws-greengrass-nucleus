/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.testcommons.testutilities;

import com.aws.iot.evergreen.ipc.IPCService;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Slf4jLogAdapter;
import com.aws.iot.evergreen.util.Utils;
import lombok.SneakyThrows;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

@SuppressWarnings("PMD.SystemPrintln")
public class ExceptionLogProtector implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final BiConsumer<ExtensionContext, EvergreenStructuredLogMessage> listener = (c, m) -> {
        if (m.getCause() != null) {
            boolean ignored = false;
            for (Predicate<Throwable> exPred : getThrowablePredicates(c)) {
                if (exPred.test(m.getCause())) {
                    ignored = true;
                    break;
                }
            }
            if (!ignored) {
                getExceptions(c).add(m.getCause());
            }
        }
    };

    private static Collection<Predicate<Throwable>> getThrowablePredicates(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(getNs(context));
        return (Collection<Predicate<Throwable>>) store
                .getOrComputeIfAbsent("throwablePredicates", (k) -> new CopyOnWriteArraySet());
    }

    private static List<Throwable> getExceptions(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(getNs(context));
        return (List<Throwable>) store.getOrComputeIfAbsent("exceptions", (k) -> new CopyOnWriteArrayList());
    }

    private static Consumer<EvergreenStructuredLogMessage> getListener(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(getNs(context));
        return (Consumer<EvergreenStructuredLogMessage>) store.getOrComputeIfAbsent("listener",
                (k) -> (Consumer<EvergreenStructuredLogMessage>) (EvergreenStructuredLogMessage m) -> listener
                        .accept(context, m));
    }

    private static ExtensionContext.Namespace getNs(ExtensionContext context) {
        return ExtensionContext.Namespace.create(context.getUniqueId());
    }

    public static void ignoreExceptionWithMessage(ExtensionContext context, String message) {
        getThrowablePredicates(context).add((t) -> Objects.equals(t.getMessage(), message));
    }

    public static void ignoreExceptionWithMessageSubstring(ExtensionContext context, String substring) {
        getThrowablePredicates(context).add((t) -> t.getMessage() != null && t.getMessage().contains(substring));
    }

    public static void ignoreExceptionUltimateCauseWithMessage(ExtensionContext context, String message) {
        getThrowablePredicates(context).add((t) -> Objects.equals(Utils.getUltimateMessage(t), message));
    }

    public static void ignoreExceptionUltimateCauseWithMessageSubstring(ExtensionContext context, String substring) {
        getThrowablePredicates(context)
                .add((t) -> Utils.getUltimateMessage(t) != null && Utils.getUltimateMessage(t).contains(substring));
    }

    public static void ignoreExceptionOfType(ExtensionContext context, Class<? extends Throwable> clazz) {
        getThrowablePredicates(context).add((t) -> Objects.equals(t.getClass(), clazz));
    }

    public static void ignoreExceptionUltimateCauseOfType(ExtensionContext context, Class<? extends Throwable> clazz) {
        getThrowablePredicates(context).add((t) -> Objects.equals(Utils.getUltimateCause(t).getClass(), clazz));
    }

    public static void ignoreException(ExtensionContext context, Throwable expected) {
        getThrowablePredicates(context).add(t -> Objects.equals(expected, t));
    }

    public static void ignoreExceptionWithStackTraceContaining(ExtensionContext context,
                                                               Class<? extends Throwable> clazz, String trace) {
        getThrowablePredicates(context).add(t -> {
            if (!Objects.equals(t.getClass(), clazz)) {
                return false;
            }
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.toString().contains(trace)) {
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Slf4jLogAdapter.addGlobalListener(getListener(context));

        // Default ignores:

        // Ignore IPCService being interrupted while starting which can happen if we call shutdown() too quickly
        // after launch()
        ignoreExceptionWithStackTraceContaining(context, InterruptedException.class,
                IPCService.class.getName() + ".listen");

        // Ignore error from MQTT not being configured
        ignoreExceptionWithMessageSubstring(context, "[thingName cannot be empty,"
                + " certificateFilePath cannot be empty, privateKeyPath cannot be empty, rootCaPath cannot be empty,");
        // Ignore error from MQTT during shutdown
        ignoreExceptionUltimateCauseWithMessage(context, "Mqtt operation interrupted by connection shutdown");

        // Ignore exceptions trying to determine AWS region/credentials
        ignoreExceptionWithMessage(context, "Unable to load region information from any provider in the chain");
        ignoreExceptionWithMessageSubstring(context, "Failed to connect to service endpoint:");
        ignoreExceptionWithMessageSubstring(context, "Forbidden (Service: null; Status Code: 403;");

        ignoreExceptionOfType(context, RejectedExecutionException.class);
    }

    @Override
    @SneakyThrows
    public void afterEach(ExtensionContext context) throws Exception {
        Slf4jLogAdapter.removeGlobalListener(getListener(context));

        List<Throwable> exceptions = getExceptions(context);
        try {
            if (!exceptions.isEmpty()) {
                System.err.println((exceptions.size() == 1 ? "1" : "Multiple")
                        + " non-ignored exceptions occurred during test run. Failing test");
                for (Throwable ex : exceptions) {
                    ex.printStackTrace(System.err);
                }
                // Throw one exception to cause the test to fail
                throw exceptions.get(0);
            }
        } finally {
            // clear exceptions and predicates so that we start clean on the next run
            // do this in the finally so that it happens after we have thrown/printed the exceptions
            exceptions.clear();
            getThrowablePredicates(context).clear();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == ExtensionContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext;
    }
}
