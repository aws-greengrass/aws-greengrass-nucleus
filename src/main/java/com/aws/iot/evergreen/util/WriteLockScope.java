/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util;

import java.util.concurrent.locks.ReadWriteLock;

public final class WriteLockScope implements AutoCloseable {
    private final ReadWriteLock ref;
    private boolean heldRead = false;

    private WriteLockScope(ReadWriteLock ref) {
        this.ref = ref;
        if (!ref.writeLock().tryLock()) {
            ref.readLock().unlock();
            ref.writeLock().lock();
            heldRead = true;
        }
    }

    /**
     * Does an OK job at upgrading a read lock to write, and back down again, but it doesn't handle
     * if the read lock is locked more than once by the current thread.
     *
     * @param ref Lock to use
     */
    public static WriteLockScope lock(ReadWriteLock ref) {
        return new WriteLockScope(ref);
    }

    @Override
    public void close() {
        if (heldRead) {
            ref.readLock().lock();
        }
        ref.writeLock().unlock();
    }
}
