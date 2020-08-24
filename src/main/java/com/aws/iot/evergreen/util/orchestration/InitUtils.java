/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util.orchestration;

import com.aws.iot.evergreen.kernel.KernelAlternatives;

public class InitUtils extends SystemServiceUtils {
    @Override
    public boolean setupSystemService(KernelAlternatives kernelAlternatives) {
        logger.atError().log("System service registration is not implemented for this device");
        return false;
    }
}
