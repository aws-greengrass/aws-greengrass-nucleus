/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.testcommons.testutilities;

import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Log4jLogEventBuilder;
import com.aws.iot.evergreen.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class TestBase {
    // Predicate which returns TRUE if the exception should be ignored as expected
    protected Collection<Predicate<Throwable>> throwablePredicates = new LinkedList<>();
    private List<Throwable> exceptions = new LinkedList<>();
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
    }

    @AfterEach
    protected void testBaseAfter() throws Throwable {
        Log4jLogEventBuilder.removeGlobalListener(listener);
        try {
            if (!exceptions.isEmpty()) {
                if (exceptions.size() == 1) {
                    System.err.println("1 non-ignored exception occurred during test run. Failing test");
                    exceptions.get(0).printStackTrace(System.err);
                    throw exceptions.get(0);
                }

                System.err.println("Multiple non-ignored exceptions occurred during test run. Failing test");
                for (Throwable ex : exceptions) {
                    ex.printStackTrace(System.err);
                }
                // Throw one exception to cause the test to fail
                throw exceptions.get(0);
            }
        } finally {
            exceptions.clear();
            throwablePredicates.clear();
        }
    }

    protected void ignoreExceptionWithMessage(String message) {
        throwablePredicates.add((t) -> t.getMessage().equals(message));
    }

    protected void ignoreExceptionWithMessageSubstring(String substring) {
        throwablePredicates.add((t) -> t.getMessage().contains(substring));
    }

    protected void ignoreExceptionUltimateCauseWithMessage(String message) {
        throwablePredicates.add((t) -> Utils.getUltimateMessage(t).equals(message));
    }

    protected void ignoreExceptionUltimateCauseWithMessageSubstring(String substring) {
        throwablePredicates.add((t) -> Utils.getUltimateMessage(t).contains(substring));
    }

    protected void ignoreExceptionOfType(Class<? extends Throwable> clazz) {
        throwablePredicates.add((t) -> Objects.equals(t.getClass(), clazz));
    }

    protected void ignoreExceptionUltimateCauseOfType(Class<? extends Throwable> clazz) {
        throwablePredicates.add((t) -> Objects.equals(Utils.getUltimateCause(t).getClass(), clazz));
    }
}
