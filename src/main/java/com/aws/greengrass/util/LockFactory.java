/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import vendored.com.google.common.util.concurrent.CycleDetectingLockFactory;

import java.util.concurrent.locks.Lock;

public final class LockFactory {

    private static final CycleDetectingLockFactory factory =
            CycleDetectingLockFactory.newInstance(CycleDetectingLockFactory.Policies.THROW);

    private LockFactory() {
    }

    public static Lock newReentrantLock(Object self) {
        return newReentrantLock(self.getClass().getSimpleName());
    }

    public static Lock newReentrantLock(String name) {
        return factory.newReentrantLock(name);
    }
}
