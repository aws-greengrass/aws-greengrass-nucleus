/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.config;

public interface Subscriber extends Watcher {
    public void published(Configuration.WhatHappened what, Object newValue, Object oldValue);
    
}
