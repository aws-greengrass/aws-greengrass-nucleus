/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.amazonaws.services.evergreen.model.FailureHandlingPolicy;
import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.NetworkUtils;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Slf4jLogAdapter;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.iot.evergreen.deployment.IotJobsHelper.UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG;
import static com.aws.iot.evergreen.deployment.IotJobsHelper.UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG;
import static com.aws.iot.evergreen.kernel.EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
@Tag("E2E-INTRUSIVE")
public class MqttReconnectTest extends BaseE2ETestCase {

    private static final String dnsCacheTtlPropertyKey = "networkaddress.cache.ttl";
    private static final Duration DNS_CACHE_TTL = Duration.ofSeconds(10);
    private String dnsCacheTtlValue;

    @BeforeEach
    void beforeEach() throws Exception {
        // Setting the JVM TTL for DNS Name Lookups. By default it's set to -1, i.e. DNS entries are never
        // refreshed until the JVM is restarted. In this test, we break the network connection in the middle. As
        // a result, the AWS endpoint will be resolved to an unknown host for a short period. Set the TTL here to make
        // sure the unknown host entries are cleared, otherwise we will get UnknownHostException from AWS SDK clients.
        dnsCacheTtlValue = java.security.Security.getProperty(dnsCacheTtlPropertyKey);
        java.security.Security.setProperty(dnsCacheTtlPropertyKey, Long.toString(DNS_CACHE_TTL.getSeconds()));

        initKernel();

        Path localStoreContentPath = Paths.get(BaseE2ETestCase.class.getResource("local_store_content").getPath());
        // pre-load contents to package store
        FileUtils.copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath());
    }

    @AfterEach
    void afterEach() {
        // Reset to the configuration to previous setting or -1 so that DNS entries are never refreshed
        java.security.Security.setProperty(dnsCacheTtlPropertyKey, dnsCacheTtlValue == null ? "-1" : dnsCacheTtlValue);

        if (kernel != null) {
            kernel.shutdown();
        }
        // Cleanup all IoT thing resources we created
        cleanup();
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_new_deployment_while_device_online_WHEN_mqtt_disconnects_and_reconnects_THEN_job_executes_successfully(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, MqttException.class);

        CountDownLatch jobInProgress = new CountDownLatch(1);
        CountDownLatch jobCompleted = new CountDownLatch(1);
        CountDownLatch connectionInterrupted = new CountDownLatch(1);

        // Create Job
        SetConfigurationRequest setRequest = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult = setAndPublishFleetConfiguration(setRequest);
        String jobId = publishResult.getJobId();

        // Subscribe to persisted deployment status
        Topics deploymentServiceTopics = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS);
        Topics processedDeployments = deploymentServiceTopics.lookupTopics(
                RUNTIME_STORE_NAMESPACE_TOPIC, PROCESSED_DEPLOYMENTS_TOPICS);
        processedDeployments.subscribe((whatHappened, newValue) -> {
            if (!(newValue instanceof Topic)) {
                return;
            }
            Map<String, Object> deploymentDetails = (HashMap) ((Topic) newValue).getOnce();
            if (!deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID).toString().equals(jobId)) {
                return;
            }
            String status = deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS).toString();
            if (JobStatus.IN_PROGRESS.toString().equals(status)) {
                jobInProgress.countDown();
            } else if (jobInProgress.getCount() <= 0 && JobStatus.SUCCEEDED.toString().equals(status)) {
                jobCompleted.countDown();
            }
        });

        kernel.launch();

        assertTrue(jobInProgress.await(5, TimeUnit.MINUTES));
        NetworkUtils networkUtils = NetworkUtils.getByPlatform();
        Consumer<EvergreenStructuredLogMessage> logListener = m -> {
            String message = m.getMessage();
            if (UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG.equals(message) && m.getCause().getCause() instanceof MqttException
                    || UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG.equals(message)) {
                connectionInterrupted.countDown();
            }
        };
        try {
            Slf4jLogAdapter.addGlobalListener(logListener);
            networkUtils.disconnectMqtt();

            // Wait for the deployment to finish offline
            assertTrue(jobCompleted.await(5, TimeUnit.MINUTES));
            assertTrue(connectionInterrupted.await(2, TimeUnit.MINUTES));
        } finally {
            networkUtils.recoverMqtt();
            Slf4jLogAdapter.removeGlobalListener(logListener);
        }

        // Wait for DNS Cache to expire
        Thread.sleep(DNS_CACHE_TTL.plus(Duration.ofSeconds(1)).toMillis());

        // Wait for the IoT job to be updated and marked as successful
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, jobId, thingInfo.getThingName(),
                Duration.ofMinutes(2), s -> s.equals(JobExecutionStatus.SUCCEEDED));
    }
}
