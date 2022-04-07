/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.lifecyclemanager.Kernel;

/**
 * Interface provided by Android Service layer.
 */
public interface AndroidServiceLevelAPI {
    /**
     * Terminates service.
     *
     * @param status exit status
     */
    void terminate(int status);

    /**
     * Get Nucleus kernel instance.
     *
     * @return Running kernel instance
     */
    Kernel getKernel();
}
