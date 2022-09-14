/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusDetails {
    public static final String DETAILED_STATUS_KEY = "detailedStatus";
    public static final String FAILURE_CAUSE_KEY = "failureCause";
    public static final String ERROR_STACK_KEY = "errorStack";
    public static final String ERROR_TYPES_KEY = "errorTypes";

    private String detailedStatus;
    private String failureCause;
    private List<String> errorStack;
    private List<String> errorTypes;
}
