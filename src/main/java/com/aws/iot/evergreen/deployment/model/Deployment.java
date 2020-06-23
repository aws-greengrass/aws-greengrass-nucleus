/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Deployment {
    private String deploymentDocument;
    @EqualsAndHashCode.Include
    private DeploymentType deploymentType;
    @EqualsAndHashCode.Include
    private String id;
    private boolean isCancelled;
    //TODO: Add interface to pass a method for status update

    /**
     * Constructor for regular deployments.
     *
     * @param deploymentDocument deployment document string
     * @param deploymentType deployment type
     * @param id deployment id
     */
    public Deployment(String deploymentDocument, DeploymentType deploymentType, String id) {
        this.deploymentDocument = deploymentDocument;
        this.deploymentType = deploymentType;
        this.id = id;
    }

    /**
     * Constructor for deployment instance to be created on a cancellation event.
     *
     * @param deploymentType deployment type
     * @param id deployment id
     * @param isCancelled flag that indicates if the instance is for a cancellation event
     */
    public Deployment(DeploymentType deploymentType, String id, boolean isCancelled) {
        this.deploymentType = deploymentType;
        this.id = id;
        this.isCancelled = isCancelled;
    }

    public enum DeploymentType {
        IOT_JOBS, LOCAL
    }
}
