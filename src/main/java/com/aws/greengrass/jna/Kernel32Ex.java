/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.jna;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.win32.W32APIOptions;

public interface Kernel32Ex extends Library {
    Kernel32Ex INSTANCE = Native.load("kernel32", Kernel32Ex.class, W32APIOptions.DEFAULT_OPTIONS);
    @SuppressWarnings({"checkstyle:MethodName", "PMD.MethodNamingConventions"})
    boolean SetConsoleCtrlHandler(HandlerRoutine handlerRoutine, boolean add);
}
