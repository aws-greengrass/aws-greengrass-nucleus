/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCEventStreamAgent;
import com.aws.greengrass.ipc.Startable;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import javax.inject.Inject;

public class ConfigStoreIPCService implements Startable {

    private final ConfigStoreIPCEventStreamAgent eventStreamAgent;
    private final GreengrassCoreIPCService greengrassCoreIPCService;

    /**
     * Constructor.
     * 
     * @param eventStreamAgent {@link ConfigStoreIPCEventStreamAgent}
     * @param greengrassCoreIPCService {@link GreengrassCoreIPCService}
     */
    @Inject
    public ConfigStoreIPCService(ConfigStoreIPCEventStreamAgent eventStreamAgent,
            GreengrassCoreIPCService greengrassCoreIPCService) {
        this.eventStreamAgent = eventStreamAgent;
        this.greengrassCoreIPCService = greengrassCoreIPCService;
    }

    @Override
    public void startup() {
        greengrassCoreIPCService
                .setUpdateConfigurationHandler((context) -> eventStreamAgent.getUpdateConfigurationHandler(context));
        greengrassCoreIPCService.setSendConfigurationValidityReportHandler(
                (context) -> eventStreamAgent.getSendConfigurationValidityReportHandler(context));
        greengrassCoreIPCService
                .setGetConfigurationHandler((context) -> eventStreamAgent.getGetConfigurationHandler(context));
        greengrassCoreIPCService.setSubscribeToConfigurationUpdateHandler(
                (context) -> eventStreamAgent.getConfigurationUpdateHandler(context));
        greengrassCoreIPCService.setSubscribeToValidateConfigurationUpdatesHandler(
                (context) -> eventStreamAgent.getValidateConfigurationUpdatesHandler(context));

    }
}
