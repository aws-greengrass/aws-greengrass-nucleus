/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.dependency.State;

public interface GlobalStateChangeListener {
    void globalServiceStateChanged(EvergreenService l, State oldState, State newState);
}
