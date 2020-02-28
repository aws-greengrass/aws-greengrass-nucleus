/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.model;

import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@Builder
@ToString
public class DeploymentPacket {
    private HashMap<String, Object> jobDocument;
    private String deploymentId;
    private long deploymentCreationTimestamp;
    private Set<Package> resolvedPackagesToDeploy;
    private Set<PackageMetadata> proposedPackagesFromDeployment;
    private Set<String> removedTopLevelPackageNames;
    private Map<Object, Object> resolvedKernelConfig;
    private ProcessStatus processStatus;

    public static enum ProcessStatus {
        VALIDATE_AND_PARSE,
        PACKAGE_DOWNLOADING,
        PACKAGE_DOWNLOADED,
        UPDATING_KERNEL;
    }
}
