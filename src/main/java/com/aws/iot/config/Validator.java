/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.config;

public interface Validator extends Watcher {
    public Object validate(Object newValue, Object oldValue);
    
}
