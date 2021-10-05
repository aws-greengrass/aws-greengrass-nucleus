/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.util.Utils.once;

public class StubResourceController implements SystemResourceController {
    private static final Logger LOGGER = LogManager.getLogger(StubResourceController.class);

    @Override
    public void removeResourceController(GreengrassService component) {
        // no op
    }

    @Override
    public void updateResourceLimits(GreengrassService component, Map<String, Object> resourceLimit) {
        once(() -> {LOGGER.warn("System resource limits for components not supported on this platform");} );
    }

    @Override
    public void resetResourceLimits(GreengrassService component) {
        once(() -> {LOGGER.warn("System resource limits for components not supported on this platform");} );
    }

    @Override
    public void addComponentProcess(GreengrassService component, Process process) {
        // no op
    }

    @Override
    public void pauseComponentProcesses(GreengrassService component, List<Process> processes) throws IOException {
        // no op
    }

    @Override
    public void resumeComponentProcesses(GreengrassService component) throws IOException {
        // no op
    }
}
