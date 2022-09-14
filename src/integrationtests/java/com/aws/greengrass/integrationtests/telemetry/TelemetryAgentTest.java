/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.telemetry;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.telemetry.MetricsPayload;
import com.aws.greengrass.telemetry.TelemetryAgent;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.status.FleetStatusService.DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_AGENT_SERVICE_TOPICS;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.atLeast;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class TelemetryAgentTest extends BaseITCase {

    private static final int aggregateInterval = 2;
    private static final int publishInterval = 4;
    public static final String MOCK_THING_NAME = "mockThing";
    private Kernel kernel;
    @Mock
    private MqttClient mqttClient;
    @Captor
    private ArgumentCaptor<PublishRequest> captor;
    private TelemetryAgent ta;

    @BeforeEach
    void before() {
        kernel = new Kernel();
        TelemetryConfig.getInstance().telemetryLoggerNamesSet.clear();
        when(DEFAULT_HANDLER.retrieveWithDefault(any(), eq(TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC), any()))
                .thenReturn(aggregateInterval);
        when(DEFAULT_HANDLER.retrieveWithDefault(any(), eq(TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC), any()))
                .thenReturn(publishInterval);
        when(DEFAULT_HANDLER.retrieveWithDefault(any(), eq(FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC), any()))
                .thenReturn(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC);
        lenient().when(mqttClient.publish(any())).thenReturn(CompletableFuture.completedFuture(0));
    }

    @AfterEach
    void after() {
        TelemetryConfig.getInstance().telemetryLoggerNamesSet.clear();
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_kernel_running_with_telemetry_config_WHEN_launch_THEN_metrics_are_published(ExtensionContext context)
            throws InterruptedException, IOException, DeviceConfigurationException {
        // Ignore exceptions caused by mock device configs
        ignoreExceptionOfType(context, SdkClientException.class);
        ignoreExceptionOfType(context, TLSAuthException.class);
        // GIVEN
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, this.getClass().getResource("config.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.getContext().put(DeviceConfiguration.class,
                new DeviceConfiguration(kernel, MOCK_THING_NAME, "us-east-1", "us-east-1", "mock", "mock", "mock", "mock","us-east-1",
                        "mock"));

        //WHEN
        CountDownLatch telemetryRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(TELEMETRY_AGENT_SERVICE_TOPICS)) {
                if (service.getState().equals(State.RUNNING)) {
                    ta = (TelemetryAgent) service;
                    telemetryRunning.countDown();
                }
            }
        });
        kernel.launch();
        assertTrue(telemetryRunning.await(10, TimeUnit.SECONDS), "TelemetryAgent is not in RUNNING state.");
        Topics telTopics = kernel.findServiceTopic(TELEMETRY_AGENT_SERVICE_TOPICS);
        assertNotNull(telTopics);
        long lastAgg = Coerce.toLong(telTopics.find(RUNTIME_STORE_NAMESPACE_TOPIC,
                TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC));

        //wait till the first publish
        assertThat(() -> Coerce.toLong(
                telTopics.find(RUNTIME_STORE_NAMESPACE_TOPIC, TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC))
                > lastAgg, eventuallyEval(is(true), Duration.ofSeconds(publishInterval + 1)));
        assertNotNull(ta.getPeriodicPublishMetricsFuture(), "periodic publish future is not scheduled.");
        long delay = ta.getPeriodicPublishMetricsFuture().getDelay(TimeUnit.SECONDS);
        assertTrue(delay <= publishInterval);
        // telemetry logs are always written to ~root/telemetry
        assertEquals(kernel.getNucleusPaths().rootPath().resolve("telemetry"),
                TelemetryConfig.getTelemetryDirectory());
        // THEN
        boolean telemetryMessageVerified = false;
        if(delay < aggregateInterval) {
            verify(mqttClient, atLeast(0)).publish(captor.capture());
        } else {
            String telemetryPublishTopic = DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC.replace("{thingName}", MOCK_THING_NAME);
            CountDownLatch metricsPublished = new CountDownLatch(1);
            List<PublishRequest> prs = new ArrayList<>();
            when(mqttClient.publish(any(PublishRequest.class))).thenAnswer(i -> {
                Object argument = i.getArgument(0);
                PublishRequest publishRequest = (PublishRequest) argument;
                if (telemetryPublishTopic.equals(publishRequest.getTopic())) {
                    prs.add(publishRequest);
                    metricsPublished.countDown();
                }
                return CompletableFuture.completedFuture(0);
            });
            assertTrue(metricsPublished.await(30, TimeUnit.SECONDS), "Metrics not published ");

            for (PublishRequest pr : prs) {
                // filter for telemetry topic because messages published to irrelevant topics can be captured here
                if (!telemetryPublishTopic.equals(pr.getTopic())) {
                    continue;
                }
                try {
                    MetricsPayload mp = new ObjectMapper().readValue(pr.getPayload(), MetricsPayload.class);
                    assertEquals(QualityOfService.AT_LEAST_ONCE, pr.getQos());
                    assertEquals("2022-06-30", mp.getSchema());
                    // enough to verify the first message of type MetricsPayload
                    telemetryMessageVerified = true;
                    break;
                } catch (IOException e) {
                    fail("The message received at this topic is not of MetricsPayload type.", e);
                }
            }
            assertTrue(telemetryMessageVerified, "Did not see message published to telemetry metrics topic");
        }

    }
}

