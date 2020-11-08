/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.amazonaws.services.evergreen.model.ComponentInfo;
import com.amazonaws.services.evergreen.model.CreateDeploymentRequest;
import com.amazonaws.services.evergreen.model.CreateDeploymentResult;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.integrationtests.e2e.util.IotJobsUtils;
import com.aws.greengrass.integrationtests.e2e.util.NetworkUtils;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.greengrass.deployment.IotJobsHelper.UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG;
import static com.aws.greengrass.deployment.IotJobsHelper.UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
@Tag("E2E-INTRUSIVE")
class MqttReconnectTest extends BaseE2ETestCase {

    private static final String dnsCacheTtlPropertyKey = "networkaddress.cache.ttl";
    private static final Duration DNS_CACHE_TTL = Duration.ofSeconds(10);
    private String dnsCacheTtlValue;

    protected MqttReconnectTest() throws Exception {
        super();
    }

    @BeforeEach
    void beforeEach() throws Exception {
        // Setting the JVM TTL for DNS Name Lookups. By default it's set to -1, i.e. DNS entries are never
        // refreshed until the JVM is restarted. In this test, we break the network connection in the middle. As
        // a result, the AWS endpoint will be resolved to an unknown host for a short period. Set the TTL here to make
        // sure the unknown host entries are cleared, otherwise we will get UnknownHostException from AWS SDK clients.
        dnsCacheTtlValue = java.security.Security.getProperty(dnsCacheTtlPropertyKey);
        java.security.Security.setProperty(dnsCacheTtlPropertyKey, Long.toString(DNS_CACHE_TTL.getSeconds()));

        initKernel();
        kernel.getNucleusPaths().setComponentStorePath(e2eTestPkgStoreDir);
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


    // GG_NEEDS_REVIEW: TODO: Fix flaky test https://sim.amazon.com/issues/P40525318
    @Disabled
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_new_deployment_while_device_online_WHEN_mqtt_disconnects_and_reconnects_THEN_job_executes_successfully(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, MqttException.class);
        ignoreExceptionUltimateCauseOfType(context, TimeoutException.class);
        ignoreExceptionWithMessage(context,
                "No valid versions were found for this package based on provided requirement");

        CountDownLatch jobInProgress = new CountDownLatch(1);
        CountDownLatch jobCompleted = new CountDownLatch(1);
        CountDownLatch connectionInterrupted = new CountDownLatch(1);

        // Create Job
        CreateDeploymentRequest createDeploymentRequest = new CreateDeploymentRequest()
                .addComponentsEntry("CustomerApp", new ComponentInfo().withVersion("1.0.0"));
        CreateDeploymentResult result = draftAndCreateDeployment(createDeploymentRequest);
        String jobId = result.getJobId();

        // Subscribe to persisted deployment status
        Topics deploymentServiceTopics =
                kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS);
        Topics processedDeployments =
                deploymentServiceTopics.lookupTopics(RUNTIME_STORE_NAMESPACE_TOPIC, PROCESSED_DEPLOYMENTS_TOPICS);
        processedDeployments.subscribe((whatHappened, newValue) -> {
            if (!(newValue instanceof Topic)) {
                return;
            }
            if (newValue.childOf(DEPLOYMENT_STATUS_KEY_NAME)) {
                newValue = newValue.parent;
            } else {
                return;
            }

            Map<String, Object> deploymentDetails = ((Topics) newValue).toPOJO();
            if (!deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME).toString().equals(jobId)) {
                return;
            }
            String status = deploymentDetails.get(DEPLOYMENT_STATUS_KEY_NAME).toString();
            if (JobStatus.IN_PROGRESS.toString().equals(status)) {
                jobInProgress.countDown();
            } else if (jobInProgress.getCount() <= 0 && JobStatus.SUCCEEDED.toString().equals(status)) {
                jobCompleted.countDown();
            }
        });

        kernel.launch();

        assertTrue(jobInProgress.await(2, TimeUnit.MINUTES));

        NetworkUtils networkUtils = NetworkUtils.getByPlatform();
        Consumer<GreengrassLogMessage> logListener = m -> {
            String message = m.getMessage();
            if (UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG.equals(message) && m.getCause()
                    .getCause() instanceof MqttException || UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG
                    .equals(message)) {
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
        // The reason for making the timeout as 7 min is because it has been observed that if the update job status was
        // invoked just before the connection recovers it can block the call for total timeout of 5 mins,
        // without successfully updating the status of the job in cloud. After this timeout expires the status will
        // be updated again as part of the onConnectionResumed callback. Additional 2 mins are for this status
        // to get updated
        IotJobsUtils
                .waitForJobExecutionStatusToSatisfy(iotClient, jobId, thingInfo.getThingName(), Duration.ofMinutes(7),
                        s -> s.equals(JobExecutionStatus.SUCCEEDED));
    }
}
