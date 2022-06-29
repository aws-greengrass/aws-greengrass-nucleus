/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.telemetry.AggregatedNamespaceData;
import com.aws.greengrass.telemetry.MetricsPayload;
import com.aws.greengrass.telemetry.TelemetryAgent;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testing.TestFeatureParameterInterface;
import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.SerializerFactory;
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
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.PutComponentMetricResponseHandler;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.MetricUnitType;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricRequest;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricResponse;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.integrationtests.ipc.PutComponentMetricsTestUtils.generateComponentRequest;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.status.FleetStatusService.DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_AGENT_SERVICE_TOPICS;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.atLeast;

@ExtendWith({GGExtension.class, MockitoExtension.class})
public class IPCComponentMetricsTest extends BaseITCase {
    private static final int aggregateInterval = 2;
    private static final int publishInterval = 4;
    public static final String MOCK_THING_NAME = "mockThing";

    private static final String STREAM_MANAGER_NAME = "aws.greengrass.StreamManager";
    private static final String TEST_SERVICE_NAME = "aws.greengrass.foo";
    private static final String INVALID_TEST_SERVICE_NAME = "com.example.foo";
    private static final String AGGREGATED_METRICS_LOG_FILE = "AggregateMetrics.log";
    private static final String METRIC_NAME = "BytesAppended";

    private Kernel kernel;
    @Mock
    private MqttClient mqttClient;
    @Captor
    private ArgumentCaptor<PublishRequest> captor;
    private TelemetryAgent ta;
    @Mock
    private TestFeatureParameterInterface DEFAULT_HANDLER;


    @BeforeEach
    void before(ExtensionContext context) {
        // Ignore exceptions caused by mock device configs
        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionOfType(context, SdkClientException.class);
        ignoreExceptionOfType(context, TLSAuthException.class);
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");

        kernel = new Kernel();

        when(DEFAULT_HANDLER.retrieveWithDefault(any(), eq(TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC),
                any())).thenReturn(aggregateInterval);
        when(DEFAULT_HANDLER.retrieveWithDefault(any(), eq(TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC),
                any())).thenReturn(publishInterval);
        when(DEFAULT_HANDLER.retrieveWithDefault(any(), eq(FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC),
                any())).thenReturn(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC);

        // Unable to reproduce on my dev machine, when run as github workflow, ScheduledExecutor throws
        // RejectedExecutionException. TestFeatureParameters seems to be having some old handlers. Clearing previous
        // handlers here
        TestFeatureParameters.clearHandlerCallbacks();
        TestFeatureParameters.internalEnableTestingFeatureParameters(DEFAULT_HANDLER);
        lenient().when(mqttClient.publish(any())).thenReturn(CompletableFuture.completedFuture(0));
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
        TestFeatureParameters.internalDisableTestingFeatureParameters();
    }

    @Test
    void GIVEN_componentMetricsClient_WHEN_stream_manager_calls_putComponentMetric_with_valid_request_THEN_succeeds()
            throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("putComponentMetric-SM.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.getContext().put(DeviceConfiguration.class,
                new DeviceConfiguration(kernel, MOCK_THING_NAME, "us-east-1", "us-east-1", "mock", "mock", "mock",
                        "us-east-1", "mock"));
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

        PutComponentMetricRequest request;
        Path telemetryFolderPath;
        try (EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                STREAM_MANAGER_NAME)) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);
            request = generateComponentRequest(METRIC_NAME, String.valueOf(MetricUnitType.COUNT));

            PutComponentMetricResponseHandler putComponentMetricResponseHandler =
                    ipcClient.putComponentMetric(request, Optional.empty());
            PutComponentMetricResponse putComponentMetricResponse =
                    putComponentMetricResponseHandler.getResponse().get(10, TimeUnit.SECONDS);
            assertNotNull(putComponentMetricResponse);
            telemetryFolderPath = kernel.getNucleusPaths().rootPath().resolve("telemetry");
            assertEquals(telemetryFolderPath, TelemetryConfig.getTelemetryDirectory());
            assertTrue(new File(telemetryFolderPath.resolve(STREAM_MANAGER_NAME + ".log")
                            .toUri()).exists());
        }

        assertTrue(telemetryRunning.await(10, TimeUnit.SECONDS), "TelemetryAgent is not in RUNNING state.");
        Topics telTopics = kernel.findServiceTopic(TELEMETRY_AGENT_SERVICE_TOPICS);
        assertNotNull(telTopics);
        long lastAgg = Coerce.toLong(
                telTopics.find(RUNTIME_STORE_NAMESPACE_TOPIC, TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC));

        // assert first publish
        assertThat(() -> Coerce.toLong(
                telTopics.find(RUNTIME_STORE_NAMESPACE_TOPIC, TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC))
                > lastAgg, eventuallyEval(is(true), Duration.ofSeconds(publishInterval + 1)));
        assertNotNull(ta.getPeriodicPublishMetricsFuture(), "periodic publish future is not scheduled.");
        long delay = ta.getPeriodicPublishMetricsFuture().getDelay(TimeUnit.SECONDS);
        assertTrue(delay <= publishInterval);

        Path aggregatedMetricsFilePath = telemetryFolderPath.resolve(AGGREGATED_METRICS_LOG_FILE);
        assertThat(aggregatedMetricsFilePath.toFile(), anExistingFile());

        // THEN validate Aggregated metrics from Telemetry are as expected.
        boolean telemetryMessageVerified = false;
        if (delay < aggregateInterval) {
            verify(mqttClient, atLeast(0)).publish(captor.capture());
        } else {
            // Validate that Aggregated Metrics log contain correct aggregated values
            double aggregatedMetricSumFromRequest =
                    request.getMetrics().stream().map(metric -> metric.getValue()).mapToDouble(Coerce::toDouble).sum();
            double aggregatedMetricSumFromFile = getAggregatedSumFromFile(aggregatedMetricsFilePath);
            assertEquals(aggregatedMetricSumFromRequest, aggregatedMetricSumFromFile);

            verify(mqttClient, atLeastOnce()).publish(captor.capture());
            List<PublishRequest> prs = captor.getAllValues();

            String telemetryPublishTopic =
                    DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC.replace("{thingName}", MOCK_THING_NAME);
            for (PublishRequest pr : prs) {
                // filter for telemetry topic because messages published to irrelevant topics can be captured here
                if (!telemetryPublishTopic.equals(pr.getTopic())) {
                    continue;
                }
                try {
                    MetricsPayload mp = new ObjectMapper().readValue(pr.getPayload(), MetricsPayload.class);
                    assertEquals(QualityOfService.AT_LEAST_ONCE, pr.getQos());
                    assertEquals("2022-06-30", mp.getSchema());

                    // valid metrics payload contains StreamManager metrics
                    List<AggregatedNamespaceData> aggregatedNamespaceData = mp.getAggregatedNamespaceData();
                    assertNotEquals(0, aggregatedNamespaceData.size());
                    assertTrue(aggregatedNamespaceData.stream()
                            .anyMatch(am -> am.getNamespace().equals(STREAM_MANAGER_NAME)));

                    // Find first StreamManager metric and validate metrics name
                    AggregatedNamespaceData streamManagerMetric =
                            aggregatedNamespaceData.stream().filter(am -> am.getNamespace().equals(STREAM_MANAGER_NAME))
                                    .findFirst().get();
                    assertTrue(streamManagerMetric.getMetrics().stream()
                            .allMatch(met -> met.getName().equals(METRIC_NAME)));

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

    @Test
    void GIVEN_componentMetricsClient_WHEN_valid_service_calls_putComponentMetric_with_no_acl_THEN_fail()
            throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("putComponentMetric-no-acl.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.getContext().put(DeviceConfiguration.class,
                new DeviceConfiguration(kernel, MOCK_THING_NAME, "us-east-1", "us-east-1", "mock", "mock", "mock",
                        "us-east-1", "mock"));
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

        try (EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                TEST_SERVICE_NAME)) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);
            PutComponentMetricRequest request = generateComponentRequest(METRIC_NAME,
                    String.valueOf(MetricUnitType.COUNT));

            ExecutionException executionException = assertThrows(ExecutionException.class, () -> {
                ipcClient.putComponentMetric(request, Optional.empty()).getResponse().get(10, TimeUnit.SECONDS);
            });
            assertTrue(executionException.getCause() instanceof UnauthorizedError);
            UnauthorizedError unauthorizedError = (UnauthorizedError) executionException.getCause();
            assertEquals("Principal aws.greengrass.foo is not authorized to perform aws.greengrass.ipc"
                            + ".componentmetric:aws.greengrass#PutComponentMetric on resource BytesAppended",
                    unauthorizedError.getMessage());
        }
    }

    @Test
    void GIVEN_componentMetricsClient_WHEN_invalid_service_calls_putComponentMetric_THEN_fail() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("putComponentMetric-invalid-service.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.getContext().put(DeviceConfiguration.class,
                new DeviceConfiguration(kernel, MOCK_THING_NAME, "us-east-1", "us-east-1", "mock", "mock", "mock",
                        "us-east-1", "mock"));
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

        try (EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                INVALID_TEST_SERVICE_NAME)) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);
            PutComponentMetricRequest request = generateComponentRequest(METRIC_NAME,
                    String.valueOf(MetricUnitType.COUNT));

            ExecutionException executionException = assertThrows(ExecutionException.class, () -> {
                ipcClient.putComponentMetric(request, Optional.empty()).getResponse().get(10, TimeUnit.SECONDS);
            });
            assertTrue(executionException.getCause() instanceof UnauthorizedError);
            UnauthorizedError unauthorizedError = (UnauthorizedError) executionException.getCause();
            assertEquals("Principal com.example.foo is not authorized to perform aws.greengrass.ipc"
                            + ".componentmetric:aws.greengrass#PutComponentMetric ",
                    unauthorizedError.getMessage());
        }
    }

    @Test
    void GIVEN_componentMetricsClient_WHEN_putComponentMetric_with_invalid_request_THEN_fail() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("putComponentMetric-SM.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.getContext().put(DeviceConfiguration.class,
                new DeviceConfiguration(kernel, MOCK_THING_NAME, "us-east-1", "us-east-1", "mock", "mock", "mock",
                        "us-east-1", "mock"));
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

        try (EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                STREAM_MANAGER_NAME)) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);
            // invalid request - unit type in request set to be empty
            PutComponentMetricRequest invalidRequest = generateComponentRequest(METRIC_NAME, "");

            Exception executionException = assertThrows(Exception.class, () -> {
                ipcClient.putComponentMetric(invalidRequest, Optional.empty()).getResponse().get(10, TimeUnit.SECONDS);
            });
            assertTrue(executionException.getCause() instanceof InvalidArgumentsError);
            InvalidArgumentsError invalidArgumentsError = (InvalidArgumentsError) executionException.getCause();
            assertEquals("Invalid argument found in PutComponentMetricRequest",
                    invalidArgumentsError.getMessage());
        }
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    private double getAggregatedSumFromFile(Path logFilePath) throws IOException {
        double aggregatedSum = 0;
        try (BufferedReader reader = Files.newBufferedReader(logFilePath)) {
            assertThat(logFilePath.toFile(), anExistingFile());
            String line;
            while ((line = reader.readLine()) != null) {
                GreengrassLogMessage logMessage =
                        SerializerFactory.getFailSafeJsonObjectMapper().readValue(line, GreengrassLogMessage.class);
                AggregatedNamespaceData aggregatedData = SerializerFactory.getFailSafeJsonObjectMapper()
                        .readValue(logMessage.getMessage(), AggregatedNamespaceData.class);
                if (aggregatedData.getNamespace().equals(STREAM_MANAGER_NAME)) {
                    aggregatedSum += aggregatedData.getMetrics().stream()
                            .mapToDouble(metric -> metric.getValue().values().stream().mapToDouble(Coerce::toDouble).sum())
                            .sum();
                }
            }
        }
        return aggregatedSum;
    }
}
