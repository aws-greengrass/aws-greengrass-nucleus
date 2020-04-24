/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

public class DeploymentJobHelper {
    public String jobId;
    public CountDownLatch jobCompleted;
    public String targetPkgName;

    public DeploymentJobHelper(String pkgName) {
        jobId = UUID.randomUUID().toString();
        jobCompleted = new CountDownLatch(1);
        targetPkgName = pkgName;
    }

    public String createIoTJobDocument() throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(
                DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                        .deploymentId(UUID.randomUUID().toString()).rootPackages(Arrays.asList(targetPkgName))
                        .deploymentPackageConfigurationList(Arrays.asList(
                                new DeploymentPackageConfiguration(targetPkgName, "1.0.0", null, null, null))).build());
    }
}
