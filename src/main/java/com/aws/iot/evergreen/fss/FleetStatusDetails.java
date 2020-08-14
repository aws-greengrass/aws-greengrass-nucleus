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
    private String ggcVersion;

    private String platform;

    private String architecture;

    private String thing;

    private OverallStatus overallStatus;

    private long sequenceNumber;

    @JsonProperty("components")
    private List<ComponentStatusDetails> componentStatusDetails;
}
