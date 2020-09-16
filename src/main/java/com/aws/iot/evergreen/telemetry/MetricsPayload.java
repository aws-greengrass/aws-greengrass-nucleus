/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.util.Chunkable;
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
public class MetricsPayload implements Chunkable<MetricsAggregator.AggregatedMetric> {
    @JsonProperty("Schema")
    @Builder.Default
    private String schema = "2020-07-30";
    @JsonProperty("ADP")
    private List<MetricsAggregator.AggregatedMetric> aggregatedMetricList;

    @Override
    public void setVariablePayload(List<MetricsAggregator.AggregatedMetric> variablePayload) {
        this.setAggregatedMetricList(variablePayload);
    }
}