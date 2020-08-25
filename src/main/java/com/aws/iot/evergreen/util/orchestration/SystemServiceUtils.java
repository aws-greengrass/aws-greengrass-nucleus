/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util.orchestration;

import com.aws.iot.evergreen.kernel.KernelAlternatives;

public interface SystemServiceUtils {
    /**
     * Setup Greengrass as a system service.
     *
     * @param kernelAlternatives KernelAlternatives instance which manages launch directory
     * @return true if setup is successful, false otherwise
     */
    boolean setupSystemService(KernelAlternatives kernelAlternatives);
}
