/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.deployment.model.FleetConfiguration;
import com.aws.iot.evergreen.deployment.model.PackageInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static com.aws.iot.evergreen.integrationtests.e2e.util.Utils.generateTestConfigurationArn;

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
                FleetConfiguration.builder()
                        .configurationArn(generateTestConfigurationArn())
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .packages(Collections.singletonMap(targetPkgName, new PackageInfo(true, "1.0.0", null)))
                        .build()
        );
    }
}
