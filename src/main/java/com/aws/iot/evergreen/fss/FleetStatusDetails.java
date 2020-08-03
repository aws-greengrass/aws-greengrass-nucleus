/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.fss;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FleetStatusDetails {
    @JsonProperty("ggcVersion")
    private String ggcVersion;

    @JsonProperty("platform")
    private String platform;

    @JsonProperty("architecture")
    private String architecture;

    @JsonProperty("thing")
    private String thing;

    @JsonProperty("overallDeviceStatus")
    private OverallStatus overallStatus;

    @JsonProperty("sequenceNumber")
    private long sequenceNumber;

    @JsonProperty("components")
    private List<ComponentStatusDetails> componentStatusDetails;
}
