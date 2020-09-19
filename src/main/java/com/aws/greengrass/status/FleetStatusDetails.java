/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status;

import com.aws.greengrass.util.Chunkable;
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
public class FleetStatusDetails implements Chunkable<ComponentStatusDetails> {
    private String ggcVersion;

    private String platform;

    private String architecture;

    private String thing;

    @JsonProperty("overallDeviceStatus")
    private OverallStatus overallStatus;

    private long sequenceNumber;

    @JsonProperty("components")
    private List<ComponentStatusDetails> componentStatusDetails;

    @Override
    public void setVariablePayload(List<ComponentStatusDetails> variablePayload) {
        this.setComponentStatusDetails(variablePayload);
    }
}
