/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class DeferUpdateRequest {

    private final String componentName;
    private final String message;
    private final String deploymentId;
    /**
     Estimated time in milliseconds after which component will be willing to be disrupted.
     If the returned value is zero the handler is granting permission to be disrupted.
     Otherwise, it will be asked again later
     */
    private final long recheckTimeInMs;
}
