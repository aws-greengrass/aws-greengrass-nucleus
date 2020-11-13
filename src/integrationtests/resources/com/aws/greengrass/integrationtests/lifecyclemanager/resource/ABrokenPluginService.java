/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager.resource;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.lifecyclemanager.PluginService;

@ImplementsService(name = "brokenPlugin")
public class ABrokenPluginService extends PluginService {
    public ABrokenPluginService(Topics topics) {
        super(topics);
        throw new RuntimeException("I'm broken!");
    }
}
