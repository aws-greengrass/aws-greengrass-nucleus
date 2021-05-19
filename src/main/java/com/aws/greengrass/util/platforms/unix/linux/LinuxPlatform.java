/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.util.platforms.SystemResourceController;
import com.aws.greengrass.util.platforms.unix.UnixPlatform;

public class LinuxPlatform extends UnixPlatform {
    SystemResourceController systemResourceController = new LinuxSystemResourceController(this);

    @Override
    public SystemResourceController getSystemResourceController() {
        return systemResourceController;
    }
}
