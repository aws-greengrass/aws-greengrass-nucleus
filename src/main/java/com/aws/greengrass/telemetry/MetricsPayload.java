/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

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
public class MetricsPayload implements Chunkable<AggregatedMetricList> {
    @JsonProperty("Schema")
    @Builder.Default
    private String schema = "2020-07-30";
    @JsonProperty("ADP")
    private List<AggregatedMetricList> aggregatedMetricList;

    @Override
    public void setVariablePayload(List<AggregatedMetricList> variablePayload) {
        this.setAggregatedMetricList(variablePayload);
    }
}