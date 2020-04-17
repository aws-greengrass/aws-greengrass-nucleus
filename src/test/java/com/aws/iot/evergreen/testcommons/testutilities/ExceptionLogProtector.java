/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.testcommons.testutilities;

import com.aws.iot.evergreen.ipc.IPCService;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Log4jLogEventBuilder;
import com.aws.iot.evergreen.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Predicate;

@SuppressWarnings("PMD.SystemPrintln")
public class ExceptionLogProtector {
    // Predicate which returns TRUE if the exception should be ignored as expected
    protected final Collection<Predicate<Throwable>> throwablePredicates = new CopyOnWriteArraySet<>();
    private final List<Throwable> exceptions = new CopyOnWriteArrayList<>();
    private final Consumer<EvergreenStructuredLogMessage> listener = (m) -> {
        if (m.getCause() != null) {
            boolean ignored = false;
            for (Predicate<Throwable> exPred : throwablePredicates) {
                if (exPred.test(m.getCause())) {
                    ignored = true;
                    break;
                }
            }
            if (!ignored) {
                exceptions.add(m.getCause());
            }
        }
    };

    @BeforeEach
    protected void testBaseBefore() {
        Log4jLogEventBuilder.addGlobalListener(listener);

        // Default ignores:

        // Ignore IPCService being interrupted while starting which can happen if we call shutdown() too quickly
        // after launch()
        ignoreExceptionWithStackTraceContaining(InterruptedException.class, IPCService.class.getName() + ".listen");

        // Ignore error from MQTT not being configured
        ignoreExceptionWithMessage("[thingName cannot be empty, certificateFilePath cannot be empty, privateKeyPath cannot be empty, rootCAPath cannot be empty, clientEndpoint cannot be empty]");
    }

    @AfterEach
    protected void testBaseAfter() throws Throwable {
        Log4jLogEventBuilder.removeGlobalListener(listener);
        try {
            if (!exceptions.isEmpty()) {
                System.err.println((exceptions.size() == 1 ? "1" : "Multiple") +
                        " non-ignored exceptions occurred during test run. Failing test");
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
            throwablePredicates.clear();
        }
    }

    protected void ignoreExceptionWithMessage(String message) {
        throwablePredicates.add((t) -> Objects.equals(t.getMessage(), message));
    }

    protected void ignoreExceptionWithMessageSubstring(String substring) {
        throwablePredicates.add((t) -> t.getMessage() != null && t.getMessage().contains(substring));
    }

    protected void ignoreExceptionUltimateCauseWithMessage(String message) {
        throwablePredicates.add((t) -> Objects.equals(Utils.getUltimateMessage(t), message));
    }

    protected void ignoreExceptionUltimateCauseWithMessageSubstring(String substring) {
        throwablePredicates.add((t) -> Utils.getUltimateMessage(t) != null && Utils.getUltimateMessage(t).contains(substring));
    }

    protected void ignoreExceptionOfType(Class<? extends Throwable> clazz) {
        throwablePredicates.add((t) -> Objects.equals(t.getClass(), clazz));
    }

    protected void ignoreExceptionUltimateCauseOfType(Class<? extends Throwable> clazz) {
        throwablePredicates.add((t) -> Objects.equals(Utils.getUltimateCause(t).getClass(), clazz));
    }

    protected void ignoreException(Throwable expected) {
        throwablePredicates.add(t -> Objects.equals(expected, t));
    }

    protected void ignoreExceptionWithStackTraceContaining(Class<? extends Throwable> clazz, String trace) {
        throwablePredicates.add(t -> {
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
}
