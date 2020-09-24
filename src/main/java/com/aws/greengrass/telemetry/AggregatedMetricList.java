package com.aws.greengrass.telemetry;

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
public class AggregatedMetricList {
    @JsonProperty("TS")
    private Long timestamp;
    @JsonProperty("NS")
    private String namespace;
    @JsonProperty("M")
    private List<AggregatedMetric> metrics;
}
