/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.fss;

import com.aws.iot.evergreen.dependency.State;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComponentStatusDetails {
    @JsonProperty("componentName")
    private String componentName;

    @JsonProperty("version")
    private String version;

    @JsonProperty("fleetConfigArn")
    private String fleetConfigArn;

    @JsonProperty("statusDetails")
    private String statusDetails;

    @JsonProperty("status")
    private State state;
}
