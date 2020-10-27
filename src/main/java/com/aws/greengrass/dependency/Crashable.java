/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

/**
 * Like Runnable, but exceptions pass through. It is normally used in situations where
 * the caller is prepared to take corrective action if badness ensues.
 */
public interface Crashable {
    void run() throws Throwable;
}
