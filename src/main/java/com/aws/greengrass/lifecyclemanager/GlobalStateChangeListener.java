/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.dependency.State;

public interface GlobalStateChangeListener {
    void globalServiceStateChanged(GreengrassService l, State oldState, State newState);
}
