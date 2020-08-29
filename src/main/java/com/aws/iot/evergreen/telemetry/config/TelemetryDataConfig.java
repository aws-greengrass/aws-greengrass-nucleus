package com.aws.iot.evergreen.telemetry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TelemetryDataConfig {
    /*
    {
      "KernelComponents": {
        "emitFrequency": 10,
        "aggregateFrequency": 30,
        "aggregationType" : "Average"
      },
      "SystemMetrics": {
        "emitFrequency": 10,
        "aggregateFrequency": 30,
        "aggregationType" : "Average"
      },
      "Mqtt": {
        "emitFrequency": 10,
        "aggregateFrequency": 30,
        "aggregationType" : "Average"
      }
    }
    */
    private static final ObjectMapper OBJECT_MAPPER =  new ObjectMapper();
    private String metricNamespace;
    private long emitFrequency;
    private long aggregateFrequency;
    private String aggregationType;
    // Do we also want the upload frequency for each metric to be configurable?
}
