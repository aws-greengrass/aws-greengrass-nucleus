/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status.model;

import com.aws.greengrass.util.Chunkable;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    private long timestamp;

    private MessageType messageType;

    private Trigger trigger;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ChunkInfo chunkInfo;

    @JsonProperty("components")
    private List<ComponentStatusDetails> componentStatusDetails;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DeploymentInformation deploymentInformation;

    @Override
    public void setVariablePayload(List<ComponentStatusDetails> variablePayload) {
        this.setComponentStatusDetails(variablePayload);
    }

    @Override
    public void setChunkInfo(int chunkId, int totalChunks) {
        if (this.messageType == MessageType.COMPLETE && totalChunks > 1) {
            // set chunk info only if it's a complete update and the message splits into multiple chunks
            chunkInfo = new ChunkInfo(chunkId, totalChunks);
        }
    }
}
