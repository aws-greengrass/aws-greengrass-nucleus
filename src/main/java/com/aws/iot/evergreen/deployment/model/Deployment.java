/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@AllArgsConstructor
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Deployment {
    private String deploymentDocument;
    @EqualsAndHashCode.Include
    private DeploymentType deploymentType;
    @EqualsAndHashCode.Include
    private String id;
    //TODO: Add interface to pass a method for status update

    public enum DeploymentType {
        IOT_JOBS, LOCAL
    }
}
