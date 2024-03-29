/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.telemetry.models.TelemetryUnit;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedMetric {
    @JsonProperty("N")
    private String name;
    // TODO: We do not need this to be a map. This map assumes that a metric can have multiple aggregation types and
    //  values, which is incorrect. This can just be replaced by a String (for aggregation type)
    //  and an Object (for value).
    private Map<String, Object> value = new HashMap<>();
    @JsonProperty("U")
    private TelemetryUnit unit;

    @JsonAnyGetter
    public Map<String, Object> getValue() {
        return value;
    }

    public void setValue(Map<String, Object> value) {
        this.value = value;
    }

    @JsonAnySetter
    public void jsonAggregationValue(final String name, final Object value) {
        this.value.put(name, value);
    }
}
