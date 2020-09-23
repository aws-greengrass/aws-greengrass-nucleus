/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.telemetry;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.telemetry.TelemetryAgent;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_AGENT_SERVICE_TOPICS;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
class TelemetryAgentTest extends BaseITCase {
    private Kernel kernel;

    @BeforeEach
    void before(TestInfo testInfo) {
        System.out.println("Running test: " + testInfo.getDisplayName());
        kernel = new Kernel();
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_kernel_running_with_some_config_WHEN_merge_simple_yaml_file_THEN_config_is_updated() throws Throwable {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("config.yaml").toString());
        CountDownLatch mainRunning = new CountDownLatch(2);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();
        Topics runtimeTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC,
                TELEMETRY_AGENT_SERVICE_TOPICS, RUNTIME_STORE_NAMESPACE_TOPIC);
        long lastAgg = Coerce.toLong(runtimeTopics.find(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC));
        Topics parameterTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC,
                TELEMETRY_AGENT_SERVICE_TOPICS, PARAMETERS_CONFIG_KEY);
        int aggregateInterval = Coerce.toInt(parameterTopics.find(TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC));
        int periodicInterval = Coerce.toInt(parameterTopics.find(TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC));

        //telemetry configurations are set correctly
        assertEquals(3, aggregateInterval);
        assertEquals(10, periodicInterval);

        //wait for atleast 4 seconds as the first aggregation occurs at 3 second.
        Thread.sleep(4000);
        assertTrue(Coerce.toLong(runtimeTopics.find(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC)) > lastAgg);

        TelemetryAgent ma = (TelemetryAgent) kernel.locate("TelemetryAgent");
        long delay = ma.getPeriodicPublishMetricsFuture().getDelay(TimeUnit.SECONDS);
        assertTrue(delay < periodicInterval);

        // telemetry logs are always written to ~root/telemetry
        assertEquals(kernel.getRootPath().resolve("telemetry"), TelemetryConfig.getTelemetryDirectory());
    }
}

