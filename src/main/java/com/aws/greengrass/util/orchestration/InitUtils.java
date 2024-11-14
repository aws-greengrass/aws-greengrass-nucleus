/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.orchestration;

import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.NucleusPaths;

public class InitUtils implements SystemServiceUtils {
    protected static final Logger logger = LogManager.getLogger(InitUtils.class);

    @Override
    public boolean setupSystemService(KernelAlternatives kernelAlternatives, NucleusPaths nucleusPaths, boolean start) {
        logger.atError().log("System service registration is not implemented for this device");
        return false;
    }
}
