/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.lifecyclemanager.GreengrassService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class StubResourceController implements SystemResourceController {

    @Override
    public void removeResourceController(GreengrassService component) {
        // no op
    }

    @Override
    public void updateResourceLimits(GreengrassService component, Map<String, Object> resourceLimit) {
        // no op
    }

    @Override
    public void resetResourceLimits(GreengrassService component) {
        // no op
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
