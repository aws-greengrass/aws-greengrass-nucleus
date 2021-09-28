/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.jna;

import com.sun.jna.win32.StdCallLibrary;

public interface HandlerRoutine extends StdCallLibrary.StdCallCallback {
    long callback(long dwCtrlType);
}
