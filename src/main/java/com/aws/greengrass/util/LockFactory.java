/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import vendored.com.google.common.util.concurrent.CycleDetectingLockFactory;

import java.util.concurrent.locks.Lock;

public final class LockFactory {
    private static CycleDetectingLockFactory.Policy policy;

    // Setup factory to throw exceptions when we are testing.
    // We detect that we are testing by checking for the JUnit @Test annotation in our classpath.
    static {
        try {
            Class.forName("org.junit.jupiter.api.Test");
            policy = CycleDetectingLockFactory.Policies.THROW;
        } catch (ClassNotFoundException e) {
            policy = CycleDetectingLockFactory.Policies.DISABLED;
        }
    }

    private static final CycleDetectingLockFactory factory =
            CycleDetectingLockFactory.newInstance(policy);

    private LockFactory() {
    }

    public static Lock newReentrantLock(Object self) {
        return newReentrantLock(self.getClass().getSimpleName());
    }

    public static Lock newReentrantLock(String name) {
        return factory.newReentrantLock(name);
    }
}
