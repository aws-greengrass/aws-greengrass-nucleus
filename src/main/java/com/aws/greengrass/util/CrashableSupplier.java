/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

/**
 * Like Supplier, but exceptions pass through. It is normally used in situations where the caller is prepared to take
 * corrective action on the exception.
 */
@FunctionalInterface
public interface CrashableSupplier<R, E extends Throwable> {
    R apply() throws E;
}
