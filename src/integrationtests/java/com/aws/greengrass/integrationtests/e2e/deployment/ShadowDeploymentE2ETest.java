/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.WrapperMqttClientConnection;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotshadow.IotShadowClient;
import software.amazon.awssdk.iot.iotshadow.model.GetNamedShadowSubscriptionRequest;
import software.amazon.awssdk.iot.iotshadow.model.ShadowState;
import software.amazon.awssdk.iot.iotshadow.model.UpdateNamedShadowRequest;
import software.amazon.awssdk.iot.iotshadow.model.UpdateNamedShadowSubscriptionRequest;
import software.amazon.awssdk.services.greengrassv2.model.ComponentConfigurationUpdate;
import software.amazon.awssdk.services.greengrassv2.model.ComponentDeploymentSpecification;
import software.amazon.awssdk.services.greengrassv2.model.CreateDeploymentRequest;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.ShadowDeploymentListener.DEPLOYMENT_SHADOW_NAME;
import static com.aws.greengrass.status.DeploymentInformation.STATUS_KEY;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
@Tag("E2E")
public class ShadowDeploymentE2ETest extends BaseE2ETestCase {
    protected ShadowDeploymentE2ETest() throws Exception {
        super();
    }
    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
        // Cleanup all IoT thing resources we created
        cleanup();
    }

    @BeforeEach
    void launchKernel() throws Exception {
        initKernel();
        kernel.launch();
    }

    @Test
    void GIVEN_kernel_running_WHEN_device_deployment_adds_packages_THEN_new_services_should_be_running()
            throws Exception {
        CountDownLatch cdlDeploymentFinished = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getMessage() != null && m.getMessage().contains("Current deployment finished")) {
                cdlDeploymentFinished.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        CreateDeploymentRequest createDeploymentRequest =
                CreateDeploymentRequest.builder().targetArn(thingInfo.getThingArn()).components(
                        Utils.immutableMap("CustomerApp",
                                ComponentDeploymentSpecification.builder().componentVersion("1.0.0")
                                        .configurationUpdate(ComponentConfigurationUpdate.builder()
                                                .merge("{\"sampleText\":\"FCS integ test\"}").build()).build(),
                                "SomeService",
                                ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        draftAndCreateDeployment(createDeploymentRequest);
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));

        IotShadowClient shadowClient =
                new IotShadowClient(new WrapperMqttClientConnection(kernel.getContext().get(MqttClient.class)));

        UpdateNamedShadowSubscriptionRequest req = new UpdateNamedShadowSubscriptionRequest();
        req.shadowName = DEPLOYMENT_SHADOW_NAME;
        req.thingName = thingInfo.getThingName();
        CountDownLatch reportInProgressCdl = new CountDownLatch(1);
        CountDownLatch reportSucceededCdl = new CountDownLatch(1);
        shadowClient.SubscribeToUpdateNamedShadowAccepted(req, QualityOfService.AT_LEAST_ONCE, (response) -> {
            try {
                logger.info("Got shadow update: {}", new ObjectMapper().writeValueAsString(response));
            } catch (JsonProcessingException e) {
                // ignore
            }
            if (response.state.reported == null) {
                return;
            }
            String reportedStatus = (String) response.state.reported.get(STATUS_KEY);
            if (JobStatus.IN_PROGRESS.toString().equals(reportedStatus)) {
                reportInProgressCdl.countDown();
            } else if (JobStatus.SUCCEEDED.toString().equals(reportedStatus)) {
                reportSucceededCdl.countDown();
            }
        });
        // wait for the shadow's reported section to be updated
        assertTrue(reportInProgressCdl.await(30, TimeUnit.SECONDS));
        assertTrue(reportSucceededCdl.await(30, TimeUnit.SECONDS));

        // deployment should succeed
        assertTrue(cdlDeploymentFinished.await(30, TimeUnit.SECONDS));
        Slf4jLogAdapter.removeGlobalListener(listener);
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("SomeService")::getState, eventuallyEval(is(State.FINISHED)));
    }

    @Test
    void GIVEN_device_deployment_WHEN_shadow_update_messages_gets_delivered_out_of_order_THEN_shadow_updated_with_latest_deployment_status()
            throws Exception {
        CreateDeploymentRequest createDeploymentRequest =
                CreateDeploymentRequest.builder().targetArn(thingInfo.getThingArn()).components(
                        Utils.immutableMap("CustomerApp",
                                ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build(),
                                "SomeService",
                                ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        draftAndCreateDeployment(createDeploymentRequest);
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));

        IotShadowClient shadowClient =
                new IotShadowClient(new WrapperMqttClientConnection(kernel.getContext().get(MqttClient.class)));

        UpdateNamedShadowSubscriptionRequest req = new UpdateNamedShadowSubscriptionRequest();
        req.shadowName = DEPLOYMENT_SHADOW_NAME;
        req.thingName = thingInfo.getThingName();
        CountDownLatch reportSucceededCdl = new CountDownLatch(1);
        CountDownLatch deviceSyncedStateToSucceededCdl = new CountDownLatch(1);
        AtomicReference<HashMap<String, Object>> reportedSection = new AtomicReference<>();
        AtomicReference<Integer> shadowVersionWhenDeviceFirstReportedSuccess = new AtomicReference<>();
        AtomicReference<Integer> shadowVersionWhenDeviceReportedInProgress = new AtomicReference<>();
        shadowClient.SubscribeToUpdateNamedShadowAccepted(req, QualityOfService.AT_LEAST_ONCE, (response) -> {
            try {
                logger.info("Got shadow update: {}", new ObjectMapper().writeValueAsString(response));
            } catch (JsonProcessingException e) {
                // ignore
            }
            if (response.state.reported == null) {
                return;
            }
            String reportedStatus = (String) response.state.reported.get(STATUS_KEY);
            if (JobStatus.IN_PROGRESS.toString().equals(reportedStatus)) {
                reportedSection.set(response.state.reported);
                shadowVersionWhenDeviceReportedInProgress.set(response.version);
            } else if (JobStatus.SUCCEEDED.toString().equals(reportedStatus)) {
                // when device first reports success reportSucceededCdl is counted down, when the device syncs the shadow
                // state to SUCCESS second time the shadow version
                if(reportSucceededCdl.getCount() == 0 && response.version > shadowVersionWhenDeviceFirstReportedSuccess.get()){
                    deviceSyncedStateToSucceededCdl.countDown();
                }
                shadowVersionWhenDeviceFirstReportedSuccess.set(response.version);
                reportSucceededCdl.countDown();
            }
        });
        //waiting for the device to report success
        assertTrue(reportSucceededCdl.await(30, TimeUnit.SECONDS));

        //Updating the shadow with deployment status IN_PROGRESS to simulate out-of-order update of shadow
        ShadowState shadowState = new ShadowState();
        shadowState.reported = reportedSection.get();
        UpdateNamedShadowRequest updateNamedShadowRequest = new UpdateNamedShadowRequest();
        updateNamedShadowRequest.shadowName = DEPLOYMENT_SHADOW_NAME;
        updateNamedShadowRequest.thingName = thingInfo.getThingName();
        updateNamedShadowRequest.state = shadowState;
        shadowClient.PublishUpdateNamedShadow(updateNamedShadowRequest, QualityOfService.AT_LEAST_ONCE)
                .get(30, TimeUnit.SECONDS);

        // verify that the device updates shadow state to SUCCEEDED
        assertTrue(deviceSyncedStateToSucceededCdl.await(30, TimeUnit.SECONDS));


        //Updating the shadow with a lower version number to trigger a message to /update/rejected event
        shadowState = new ShadowState();
        shadowState.reported = reportedSection.get();
        updateNamedShadowRequest = new UpdateNamedShadowRequest();
        updateNamedShadowRequest.shadowName = DEPLOYMENT_SHADOW_NAME;
        updateNamedShadowRequest.thingName = thingInfo.getThingName();
        updateNamedShadowRequest.state = shadowState;
        updateNamedShadowRequest.version = shadowVersionWhenDeviceReportedInProgress.get();
        shadowClient.PublishUpdateNamedShadow(updateNamedShadowRequest, QualityOfService.AT_LEAST_ONCE)
                .get(30, TimeUnit.SECONDS);


        CountDownLatch deviceRetrievedShadowCdl = new CountDownLatch(1);
        GetNamedShadowSubscriptionRequest getNamedShadowSubscriptionRequest
                = new GetNamedShadowSubscriptionRequest();
        getNamedShadowSubscriptionRequest.shadowName = DEPLOYMENT_SHADOW_NAME;
        getNamedShadowSubscriptionRequest.thingName = thingInfo.getThingName();
        shadowClient.SubscribeToGetNamedShadowAccepted(getNamedShadowSubscriptionRequest,
                QualityOfService.AT_MOST_ONCE,
                getShadowResponse -> {
                    deviceRetrievedShadowCdl.countDown();
                }).get(30, TimeUnit.SECONDS);

        // verify that the device retrieved the shadow when an update operation was rejected.
        assertTrue(deviceRetrievedShadowCdl.await(30, TimeUnit.SECONDS));
    }


    @Test
    void GIVEN_kernel_running_WHEN_device_deployment_has_large_config_THEN_config_downloaded()
            throws Exception {
        CountDownLatch cdlDeploymentFinished = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getMessage() != null && m.getMessage().contains("Current deployment finished")) {
                cdlDeploymentFinished.countDown();
            }
        };
        // Threshold for triggering large config is 8 KB for shadow deployment. Using a 32000 bytes string.
        String largeConfigValue = StringUtils.repeat("*", 32 * 1000);
        try (AutoCloseable l = TestUtils.createCloseableLogListener(listener)) {
            CreateDeploymentRequest createDeploymentRequest =
                    CreateDeploymentRequest.builder().targetArn(thingInfo.getThingArn()).components(
                            Utils.immutableMap("CustomerApp",
                                    ComponentDeploymentSpecification.builder().componentVersion("1.0.0")
                                            .configurationUpdate(ComponentConfigurationUpdate.builder()
                                                    .merge("{\"largeConfigKey\":\"" + largeConfigValue + "\"}")
                                                    .build()).build())).build();
            draftAndCreateDeployment(createDeploymentRequest);
            assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));

            IotShadowClient shadowClient =
                    new IotShadowClient(new WrapperMqttClientConnection(kernel.getContext().get(MqttClient.class)));

            UpdateNamedShadowSubscriptionRequest req = new UpdateNamedShadowSubscriptionRequest();
            req.shadowName = DEPLOYMENT_SHADOW_NAME;
            req.thingName = thingInfo.getThingName();
            CountDownLatch reportInProgressCdl = new CountDownLatch(1);
            CountDownLatch reportSucceededCdl = new CountDownLatch(1);
            shadowClient.SubscribeToUpdateNamedShadowAccepted(req, QualityOfService.AT_LEAST_ONCE, (response) -> {
                try {
                    logger.info("Got shadow update: {}", new ObjectMapper().writeValueAsString(response));
                } catch (JsonProcessingException e) {
                    // ignore
                }
                if (response.state.reported == null) {
                    return;
                }
                String reportedStatus = (String) response.state.reported.get(STATUS_KEY);
                if (JobStatus.IN_PROGRESS.toString().equals(reportedStatus)) {
                    reportInProgressCdl.countDown();
                } else if (JobStatus.SUCCEEDED.toString().equals(reportedStatus)) {
                    reportSucceededCdl.countDown();
                }
            });
            // wait for the shadow's reported section to be updated
            assertTrue(reportInProgressCdl.await(600, TimeUnit.SECONDS));
            assertTrue(reportSucceededCdl.await(600, TimeUnit.SECONDS));

            // deployment should succeed
            assertTrue(cdlDeploymentFinished.await(30, TimeUnit.SECONDS));
            Slf4jLogAdapter.removeGlobalListener(listener);
            assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
            Topics customerApp = getCloudDeployedComponent("CustomerApp").getConfig();
            assertEquals(largeConfigValue, Coerce.toString(customerApp.find("configuration", "largeConfigKey")));
        }
    }
}
