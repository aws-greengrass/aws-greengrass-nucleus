/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class Deployment {
    private String deploymentDocument;
    private DeploymentType deploymentType;
    private String id;
    //TODO: Add interface to pass a method for status update

    public enum DeploymentType {
        IOT_JOBS;
    }
}
