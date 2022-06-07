/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import android.content.Context;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;

import java.io.IOException;

public interface ProvisionManager {

    /**
     * Is Nucleus already provisioned.
     *
     * @return true when Nucleus is provisioned
     */
    boolean isProvisioned();

    /**
     * Execute automated provisioning.
     *
     * @param context context.
     * @param config new provisioning config
     * @throws Exception on errors
     */
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    void executeProvisioning(Context context, @NonNull JsonNode config) throws Exception;

    /**
     * Drop Nucleus config files.
     *
     */
    void clearNucleusConfig();

    /**
     * Drop IoT thing credentials.
     *
     * @throws IOException on errors
     */
    void clearProvision() throws IOException;

    /**
     * Get thing name.
     *
     * @return Thing name.
     */
    String getThingName();
}
