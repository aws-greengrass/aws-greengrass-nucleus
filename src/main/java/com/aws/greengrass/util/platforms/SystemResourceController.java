/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.lifecyclemanager.GreengrassService;

import java.util.Map;

public interface SystemResourceController {
    /**
     * Remove the resource controller. This method should be called when an existing generic external service is
     * removed.
     *
     * @param component a generic external service
     */
    void removeResourceController(GreengrassService component);

    /**
     * Update the resource limits for a generic external service.
     *
     * @param component     a generic external service
     * @param resourceLimit resource limits
     */
    void updateResourceLimits(GreengrassService component, Map<String, Object> resourceLimit);

    /**
     * Reset the resource limits of a generic external service to system default.
     *
     * @param component a generic external service
     */
    void resetResourceLimits(GreengrassService component);

    /**
     * Add the processes of a generic external service to the resource controller.
     *
     * @param component a generic external service
     * @param process   the first process of the external service
     */
    void addComponentProcess(GreengrassService component, Process process);
}
