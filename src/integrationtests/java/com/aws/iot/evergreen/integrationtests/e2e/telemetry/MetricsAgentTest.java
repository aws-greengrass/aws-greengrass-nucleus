/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.telemetry;

import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.SubscribeRequest;
import com.aws.iot.evergreen.telemetry.MetricsAgent;
import com.aws.iot.evergreen.telemetry.MetricsAggregator;
import com.aws.iot.evergreen.telemetry.MetricsPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

// TODO : Complete the tests
@SuppressWarnings("PMD.CloseResource")
@Tag("E2E")
public class MetricsAgentTest extends BaseE2ETestCase {
    private static final ObjectMapper DESERIALIZER = new ObjectMapper();

    @AfterEach
    void afterEach() {
        try {
            if (kernel != null) {
                kernel.shutdown();
            }
        } finally {
            // Cleanup all IoT thing resources we created
            cleanup();
        }
    }

    @BeforeEach
    void launchKernel() throws Exception {
        initKernel();
        kernel.launch();

        // TODO: Without this sleep, DeploymentService sometimes is not able to pick up new IoT job created here,
        // causing these tests to fail. There may be a race condition between DeploymentService startup logic and
        // creating new IoT job here.
        TimeUnit.SECONDS.sleep(10);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_kernel_running_with_deployed_services_WHEN_deployment_finishes_THEN_fss_data_is_uploaded() throws Exception {
        MqttClient client = kernel.getContext().get(MqttClient.class);

        CountDownLatch cdl = new CountDownLatch(2);
        AtomicReference<List<MqttMessage>> mqttMessagesList = new AtomicReference<>();
        mqttMessagesList.set(new ArrayList<>());
        // TODO: Make the publish topic configurable?
        client.subscribe(SubscribeRequest.builder()
                .topic(MetricsAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC.replace("{thingName}", thingInfo.getThingName()))
                .callback((m) -> {
                    cdl.countDown();
                    mqttMessagesList.get().add(m);
                }).build());
        Topics maTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC,
                MetricsAgent.METRICS_AGENT_SERVICE_TOPICS);
        maTopics.lookup(PARAMETERS_CONFIG_KEY, MetricsAgent.getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC())
                .withValue(5);
        maTopics.lookup(PARAMETERS_CONFIG_KEY, MetricsAgent.getTELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC())
                .withValue(3);
        //Deployment to have some services running in Kernel
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"))
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));


        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        List<List<MetricsAggregator.AggregatedMetric>> metrics = new ArrayList<>();
        for (MqttMessage mt : mqttMessagesList.get()) {
            try {
                // In this test, we filter only the metrics payload.
                MetricsPayload mp = DESERIALIZER.readValue(mt.getPayload(), MetricsPayload.class);
                metrics.add(mp.getAggregatedMetricList());
            } catch (MismatchedInputException  e) {
                // do nothing if the payload is something else
            }
        }
    }
}
