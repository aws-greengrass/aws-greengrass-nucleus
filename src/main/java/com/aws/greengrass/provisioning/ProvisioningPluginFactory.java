/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.provisioning;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ProvisioningPluginFactory {

    public <T> T getPluginInstance(Class pluginClass) throws IllegalAccessException, InstantiationException {
        return (T) pluginClass.newInstance();
    }
}
