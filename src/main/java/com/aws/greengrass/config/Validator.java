/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

/**
 * Used to validate a value being assigned to a topic. A no-op validator returns <tt>newValue</tt>. Validators are
 * called when a topic is locked, so they should be quick, not throw exceptions, and have no chance of being recursive.
 * To reject a change, return <tt>oldValue</tt>
 */
public interface Validator extends Watcher {
    Object validate(Object newValue, Object oldValue);
}
