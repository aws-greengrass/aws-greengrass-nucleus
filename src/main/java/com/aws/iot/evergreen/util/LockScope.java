/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.util;

import java.util.concurrent.locks.Lock;

public final class LockScope implements AutoCloseable {
    private final Lock ref;

    private LockScope(Lock ref) {
        this.ref = ref;
        ref.lock();
    }

    public static LockScope lock(Lock ref) {
        return new LockScope(ref);
    }

    @Override
    public void close() {
        ref.unlock();
    }
}
