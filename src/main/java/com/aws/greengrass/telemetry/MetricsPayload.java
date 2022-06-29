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
public class MetricsPayload implements Chunkable<AggregatedNamespaceData> {
    @JsonProperty("Schema")
    @Builder.Default
    private String schema = "2022-06-30";
    @JsonProperty("ADP")
    private List<AggregatedNamespaceData> aggregatedNamespaceData;

    @Override
    public void setVariablePayload(List<AggregatedNamespaceData> variablePayload) {
        this.setAggregatedNamespaceData(variablePayload);
    }

    @Override
    public void setChunkInfo(int id, int totalChunks) {
        //no-op
    }
}
