/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
public class Deployment {
    @Setter
    private DeploymentDocument deploymentDocumentObj;
    @JsonIgnore
    private String deploymentDocument;
    @EqualsAndHashCode.Include
    private DeploymentType deploymentType;
    @EqualsAndHashCode.Include
    private String id;
    @EqualsAndHashCode.Include
    private boolean isCancelled;
    @Setter
    private DeploymentStage deploymentStage;
    @Setter
    private String stageDetails;
    // GG_NEEDS_REVIEW: TODO: Add interface to pass a method for status update

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
        this.deploymentStage = DeploymentStage.DEFAULT;
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
        this.deploymentStage = DeploymentStage.DEFAULT;
    }

    /**
     * Constructor for resuming deployments after Kernel restarts.
     *
     * @param deploymentDetails deployment document object
     * @param deploymentType deployment type
     * @param id deployment id
     * @param deploymentStage deployment stage, only applicable to deployments which require Kernel restart
     */
    public Deployment(DeploymentDocument deploymentDetails, DeploymentType deploymentType, String id,
                      DeploymentStage deploymentStage) {
        this.deploymentDocumentObj = deploymentDetails;
        this.deploymentType = deploymentType;
        this.id = id;
        this.deploymentStage = deploymentStage;
    }

    public enum DeploymentType {
        IOT_JOBS, LOCAL, SHADOW
    }

    public enum DeploymentStage {
        /**
         * Deployment workflow is non-intrusive, i.e. not impacting kernel runtime
         */
        DEFAULT,

        /**
         * Deployment goes over component bootstrap steps, which can be intrusive to kernel.
         */
        BOOTSTRAP,

        /**
         * Deployment has finished bootstrap steps and is in the middle of applying all changes to Kernel.
         */
        KERNEL_ACTIVATION,

        /**
         * Deployment tries to rollback to Kernel with previous configuration, after BOOTSTRAP or
         * KERNEL_ACTIVATION fails.
         */
        KERNEL_ROLLBACK
    }

}
