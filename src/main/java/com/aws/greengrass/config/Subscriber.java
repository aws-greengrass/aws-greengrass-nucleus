/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

/**
 * A subscriber is told what Topic changed, but must look in the Topic (t.getOnce()) to get the new value. There is no
 * "old value" provided, although the publish framework endeavors to suppress notifying when the new value is the same
 * as the old value. Subscribers do not necessarily get notified on every change. If a sequence of changes happen in
 * rapid succession, they may be collapsed into one notification. This usually happens when a compound change occurs.
 */
@FunctionalInterface
public interface Subscriber extends Watcher {
    void published(WhatHappened what, Topic t);
}
