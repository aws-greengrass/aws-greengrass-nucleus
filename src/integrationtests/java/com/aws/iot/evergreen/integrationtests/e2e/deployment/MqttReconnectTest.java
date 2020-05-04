/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.NetworkUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.Utils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Log4jLogEventBuilder;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.iot.evergreen.deployment.DeploymentService.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.iot.evergreen.deployment.DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG;
import static com.aws.iot.evergreen.deployment.DeploymentService.UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
@Tag("E2E")
public class MqttReconnectTest {
    @TempDir
    static Path tempRootDir;

    private static Kernel kernel;
    private static Utils.ThingInfo thing;
    private static String thingGroupArn;
    private static final String dnsCacheTtlPropertyKey = "networkaddress.cache.ttl";
    private String dnsCacheTtlValue;

    private static final Duration DNS_CACHE_TTL = Duration.ofSeconds(10);

    @BeforeEach
    void beforeEach() throws Exception {
        // Setting the JVM TTL for DNS Name Lookups. By default it's set to -1, i.e. DNS entries are never
        // refreshed until the JVM is restarted. In this test, we break the network connection in the middle. As
        // a result, the AWS endpoint will be resolved to an unknown host for a short period. Set the TTL here to make
        // sure the unknown host entries are cleared, otherwise we will get UnknownHostException from AWS SDK clients.
        dnsCacheTtlValue = java.security.Security.getProperty(dnsCacheTtlPropertyKey);
        java.security.Security.setProperty(dnsCacheTtlPropertyKey, Long.toString(DNS_CACHE_TTL.getSeconds()));

        System.setProperty("root", tempRootDir.toAbsolutePath().toString());

        kernel = new Kernel().parseArgs("-i", MqttReconnectTest.class.getResource("blank_config.yaml").toString());
        thing = Utils.createThing();
        Utils.updateKernelConfigWithIotConfiguration(kernel, thing);
        thingGroupArn = Utils.createThingGroupAndAddThing(thing);

        Path localStoreContentPath = Paths.get(DeploymentE2ETest.class.getResource("local_store_content").getPath());
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
        Utils.cleanAllCreatedThings();
        Utils.cleanAllCreatedJobs();
        Utils.cleanAllCreatedThingGroups();
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_new_deployment_while_device_online_WHEN_mqtt_disconnects_and_reconnects_THEN_job_executes_successfully(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, MqttException.class);

        String jobId = UUID.randomUUID().toString();

        CountDownLatch jobInProgress = new CountDownLatch(1);
        CountDownLatch jobCompleted = new CountDownLatch(1);
        CountDownLatch connectionInterrupted = new CountDownLatch(1);

        // Subscribe to persisted deployment status
        Topics deploymentServiceTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC,
                DEPLOYMENT_SERVICE_TOPICS);
        Topics processedDeployments = deploymentServiceTopics.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
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

        // Create Job Doc
        String document = new ObjectMapper()
                .writeValueAsString(DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                        .deploymentId(UUID.randomUUID().toString()).rootPackages(Arrays.asList("CustomerApp"))
                        .deploymentPackageConfigurationList(Arrays.asList(
                                new DeploymentPackageConfiguration("CustomerApp", "1.0.0", null, null, null)
                        )).build());

        // Create job targeting our DUT
        String[] targets = {thingGroupArn};
        Utils.createJobWithId(document, jobId, targets);

        assertTrue(jobInProgress.await(5, TimeUnit.MINUTES));
        NetworkUtils networkUtils = NetworkUtils.getByPlatform();
        Consumer<EvergreenStructuredLogMessage> logListener = m -> {
            String message = m.getMessage();
            if (UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG.equals(message)
                    && m.getCause().getCause() instanceof MqttException
                    || UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG.equals(message)
                    && m.getCause().getCause() instanceof TimeoutException) {
                connectionInterrupted.countDown();
            }
        };
        try {
            Log4jLogEventBuilder.addGlobalListener(logListener);
            networkUtils.disconnectMqtt();

            // Wait for the deployment to finish offline
            assertTrue(jobCompleted.await(2, TimeUnit.MINUTES));
            assertTrue(connectionInterrupted.await(1, TimeUnit.MINUTES));
        } finally {
            networkUtils.recoverMqtt();
            Log4jLogEventBuilder.removeGlobalListener(logListener);
        }

        // Wait for DNS Cache to expire
        Thread.sleep(DNS_CACHE_TTL.plus(Duration.ofSeconds(1)).toMillis());

        // Wait up to 5 minutes for the IoT job to be updated
        Utils.waitForJobToComplete(jobId, Duration.ofMinutes(1));

        // Make sure that IoT Job was marked as successful
        assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thing.thingName).build())
                .execution().status());
    }
}
