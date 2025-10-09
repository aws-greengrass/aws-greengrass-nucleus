/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.lifecyclemanager.GreengrassService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface SystemResourceController {

    /**
     * Remove the resource controller. This method should be called when an existing generic external service is
     * removed.
     *
     * @param component a greengrass service instance
     */
    void removeResourceController(GreengrassService component);

    /**
     * Update the resource limits for a generic external service.
     *
     * @param component a greengrass service instance
     * @param resourceLimit resource limits
     */
    void updateResourceLimits(GreengrassService component, Map<String, Object> resourceLimit);

    /**
     * Reset the resource limits of a generic external service to system default.
     *
     * @param component a greengrass service instance
     */
    void resetResourceLimits(GreengrassService component);

    /**
     * Add the processes of a generic external service to the resource controller.
     * 
     * @param component a greengrass service instance
     * @param process the first process of the external service
     */
    void addComponentProcess(GreengrassService component, Process process);

    /**
     * Add the processes of a generic external service to the resource controller.
     *
     * @param component a greengrass service instance
     * @param processes currently alive processes for the component
     * @throws IOException on failure to pause
     */
    void pauseComponentProcesses(GreengrassService component, List<Process> processes) throws IOException;

    /**
     * Add the processes of a generic external service to the resource controller.
     *
     * @param component a greengrass service instance
     * @throws IOException on failure to resume
     */
    void resumeComponentProcesses(GreengrassService component) throws IOException;
}
