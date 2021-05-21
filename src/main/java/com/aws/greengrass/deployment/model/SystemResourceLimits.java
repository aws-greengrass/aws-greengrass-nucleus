/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SystemResourceLimits {

    LinuxSystemResourceLimits linux;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class LinuxSystemResourceLimits {
        long memory;
        double cpu;
    }
}
