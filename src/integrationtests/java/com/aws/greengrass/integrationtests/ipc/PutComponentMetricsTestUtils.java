/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import software.amazon.awssdk.aws.greengrass.model.Metric;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

public final class PutComponentMetricsTestUtils {
    private static final Random RANDOM = new Random();

    private PutComponentMetricsTestUtils() {

    }

    public static PutComponentMetricRequest generateComponentRequest(String metricName, String unitType) {
        PutComponentMetricRequest componentMetricRequest = new PutComponentMetricRequest();
        List<Metric> metrics = new ArrayList<>();
        IntStream.range(0, 4).forEach(i -> {
            Metric metric = new Metric();
            metric.setName(metricName);
            metric.setUnit(unitType);
            metric.setValue((double) RANDOM.nextInt(50));

            metrics.add(metric);
        });
        componentMetricRequest.setMetrics(metrics);

        return componentMetricRequest;
    }
}
