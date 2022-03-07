/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;

import javax.annotation.Nullable;

public interface ProvisionManager {

    /**
     * Is Nucleus already provisioned.
     *
     * @return true when Nucleus is provisioned
     */
    boolean isProvisioned();

    /**
     * Drop IoT thing credentials and Nucleus config files.
     *
     */
    void clearProvision();

    /**
     * Get Nucleus main() arguments.
     *  In addition if required copy provisioning credentials to java system properties.
     *
     * @return array of strings with argument for Nucleus main()
     * @throws Exception on errors
     */
    @NonNull
    String[] prepareArguments() throws Exception;

    /**
     * Set provisioning info in JSON format.
     *
     * @param config provisioning config
     */
    void setConfig(@Nullable JsonNode config);

    /**
     * Cleanup provisioning credentials from system properties.
     */
    public void clearSystemProperties();
}
