/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.provisioning;

import com.aws.greengrass.provisioning.exceptions.RetryableProvisioningException;

public interface DeviceIdentityInterface {

    ProvisionConfiguration updateIdentityConfiguration(ProvisionContext provisionContext)
            throws RetryableProvisioningException;

    String name();
}