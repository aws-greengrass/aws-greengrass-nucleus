/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.provisioning;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProvisionConfiguration {
    private SystemConfiguration systemConfiguration;
    private NucleusConfiguration nucleusConfiguration;

    @Setter
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SystemConfiguration {
        private String certificateFilePath;
        private String privateKeyPath;
        private String rootCAPath;
        private String thingName;
    }

    @Setter
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @SuppressFBWarnings
    public static class NucleusConfiguration {
        private String awsRegion;
        private String iotCredentialsEndpoint;
        private String iotDataEndpoint;
        private String iotRoleAlias;
    }
}
