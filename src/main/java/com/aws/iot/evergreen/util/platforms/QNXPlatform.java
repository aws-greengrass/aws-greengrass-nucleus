/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util.platforms;

import java.io.IOException;

public class QNXPlatform extends UnixPlatform {
    @Override
    public void killProcessAndChildren(Process process, boolean force) throws IOException, InterruptedException {
        killProcessAndChildrenUsingPS(process, force);
    }
}
