/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Loads docker image artifact downloader. Currently just a placeholder since the downloader is part of the nucleus but
 * when the downloader needs to be moved out into its own plugin, this service will be instantiated for the plugin.
 */
@ImplementsService(name = DockerManagerService.DOCKER_MANAGER_PLUGIN_SERVICE_NAME)
@Singleton
public class DockerManagerService extends GreengrassService {
    public static final String DOCKER_MANAGER_PLUGIN_SERVICE_NAME = "aws.greengrass.DockerManager";

    /**
     * Constructor for injection.
     *
     * @param topics topics root
     */
    @Inject
    public DockerManagerService(Topics topics) {
        super(topics);
    }
}
