package com.aws.iot.evergreen.telemetry.aggregation;

import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
public class AggregatedMetric {
    @JsonProperty("TS")
    private Long timestamp;
    @JsonProperty("NS")
    private TelemetryNamespace metricNamespace;
    @JsonProperty("M")
    private List<Metric> metric;

    @Data
    @Builder
    public static class Metric {
        @JsonProperty("N")
        private TelemetryMetricName metricName;
        @JsonProperty("V")
        private Object value;
        @JsonProperty("U")
        private TelemetryUnit metricUnit;
    }

}
