/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.Coerce;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC;

@Data
@Builder
public class TelemetryConfiguration {

    @Builder.Default
    private boolean isEnabled = true;
    @Builder.Default
    private int periodicAggregateMetricsIntervalSec = DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC;
    @Builder.Default
    private int periodicPublishMetricsIntervalSec = DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC;

    /**
     * Get the telemetry configuration from the POJO map.
     * @param pojo  POJO object.
     * @return  the telemetry configuration.
     */
    public static TelemetryConfiguration fromPojo(Map<String, Object> pojo) {
        TelemetryConfiguration telemetryConfiguration = TelemetryConfiguration.builder().build();
        int periodicAggregateMetricsIntervalSec = DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC;
        int periodicPublishMetricsIntervalSec = DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC;
        for (Map.Entry<String, Object> entry : pojo.entrySet()) {
            switch (entry.getKey()) {
                case "isEnabled":
                    telemetryConfiguration.setEnabled(Coerce.toBoolean(entry.getValue()));
                    break;
                case "periodicAggregateMetricsIntervalSec":
                    int newPeriodicAggregateMetricsIntervalSec = Coerce.toInt(entry.getValue());
                    // if the aggregation interval is smaller than it then return since we don't want to
                    // aggregate more frequently than the default.
                    if (newPeriodicAggregateMetricsIntervalSec < periodicAggregateMetricsIntervalSec) {
                        break;
                    }
                    periodicAggregateMetricsIntervalSec = newPeriodicAggregateMetricsIntervalSec;
                    break;
                case "periodicPublishMetricsIntervalSec":
                    int newPeriodicPublishMetricsIntervalSec = Coerce.toInt(entry.getValue());
                    // if the publish interval is smaller than it then return since we don't want to
                    // publish more frequently than the default.
                    if (newPeriodicPublishMetricsIntervalSec < periodicPublishMetricsIntervalSec) {
                        break;
                    }
                    periodicPublishMetricsIntervalSec = newPeriodicPublishMetricsIntervalSec;
                    break;
                default:
                    break;
            }
        }
        periodicAggregateMetricsIntervalSec = TestFeatureParameters
                .retrieveWithDefault(Double.class, TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC,
                        periodicAggregateMetricsIntervalSec).intValue();
        periodicPublishMetricsIntervalSec = TestFeatureParameters
                .retrieveWithDefault(Double.class, TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC,
                        periodicPublishMetricsIntervalSec).intValue();
        telemetryConfiguration
                .setPeriodicAggregateMetricsIntervalSec(periodicAggregateMetricsIntervalSec);
        telemetryConfiguration.setPeriodicPublishMetricsIntervalSec(periodicPublishMetricsIntervalSec);
        return telemetryConfiguration;
    }
}
