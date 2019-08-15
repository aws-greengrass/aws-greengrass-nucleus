/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.config;


public interface ChildChanged extends Watcher {
    public void childChanged(Node child);
}
