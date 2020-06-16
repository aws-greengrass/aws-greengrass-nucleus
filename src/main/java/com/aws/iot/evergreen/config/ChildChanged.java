/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

@FunctionalInterface
public interface ChildChanged extends Watcher {
    void childChanged(WhatHappened what, Node child);
}
