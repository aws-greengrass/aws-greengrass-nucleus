/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

/**
 * Like Function, but exceptions pass through. It is normally used in situations where the caller is prepared to take
 * corrective action on the exception.
 */
@FunctionalInterface
public interface CrashableFunction<T, R, E extends Exception> {

    R apply(T t) throws E;
}
