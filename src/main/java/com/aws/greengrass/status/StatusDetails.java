/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusDetails {
    public static final String DETAILED_STATUS_KEY = "detailedStatus";
    public static final String FAILURE_CAUSE_KEY = "failureCause";
    private String detailedStatus;
    private String failureCause;
}
