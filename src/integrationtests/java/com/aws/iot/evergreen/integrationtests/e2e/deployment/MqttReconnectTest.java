/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.integrationtests.e2e.util.NetworkUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.Utils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
import com.aws.iot.evergreen.util.CommitableFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_MQTT_CLIENT_ENDPOINT;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("E2E")
public class MqttReconnectTest {
    @TempDir
    static Path tempRootDir;

    private static Utils.ThingInfo thing;
    private static String rootCaFilePath;
    private static String privateKeyFilePath;
    private static String certificateFilePath;
    private static final Path LOCAL_CACHE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    private static final Duration DNS_CACHE_TTL = Duration.ofSeconds(10);

    @BeforeAll
    static void beforeAll() throws Exception {
        // Setting the JVM TTL for DNS Name Lookups. By default it's set to -1, i.e. DNS entries are never
        // refreshed until the JVM is restarted. In this test, we breaks the network connection in the middle, and as
        // a result, the AWS endpoint will be resolved as Unknown Hosts for a short period. Set the TTL here to make
        // sure the unknown entries are cleared, otherwise we will get UnknownHostException from AWS SDK clients.
        java.security.Security.setProperty("networkaddress.cache.ttl", Long.toString(DNS_CACHE_TTL.getSeconds()));
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        rootCaFilePath = tempRootDir.resolve("rootCA.pem").toString();
        privateKeyFilePath = tempRootDir.resolve("privKey.key").toString();
        certificateFilePath = tempRootDir.resolve("thingCert.crt").toString();

        // Setup IoT resources
        Utils.downloadRootCAToFile(new File(rootCaFilePath));
        thing = Utils.createThing();
        try (CommitableFile cf = CommitableFile.of(new File(privateKeyFilePath).toPath(), true)) {
            cf.write(thing.keyPair.privateKey().getBytes(StandardCharsets.UTF_8));
        }
        try (CommitableFile cf = CommitableFile.of(new File(certificateFilePath).toPath(), true)) {
            cf.write(thing.certificatePem.getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void afterAll() {
        // Cleanup all IoT thing resources we created
        Utils.cleanAllCreatedThings();
        Utils.cleanAllCreatedJobs();
    }

    @Test
    void GIVEN_new_deployment_WHEN_mqtt_disconnects_and_reconnects_THEN_job_is_successful() throws Exception {
        String jobId = UUID.randomUUID().toString();

        CountDownLatch jobInProgress = new CountDownLatch(1);
        CountDownLatch jobCompleted = new CountDownLatch(1);

        // Inject IoT resources into kernel
        Kernel kernel = new Kernel().parseArgs("-i", getClass().getResource("blank_config.yaml").toString());
        Topics deploymentServiceTopics = kernel.lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_THING_NAME).setValue(thing.thingName);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_MQTT_CLIENT_ENDPOINT).setValue(thing.endpoint);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_PRIVATE_KEY_PATH).setValue(privateKeyFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_CERTIFICATE_FILE_PATH).setValue(certificateFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_ROOT_CA_PATH).setValue(rootCaFilePath);

        Topics processedDeployments = deploymentServiceTopics.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
        processedDeployments.subscribe((whatHappened, newValue) -> {
            if (!(newValue instanceof Topic)) {
                return;
            }
            Map<String, Object> deploymentDetails = (HashMap) ((Topic) newValue).getOnce();
            if (!deploymentDetails.get("JobId").toString().equals(jobId)) {
                return;
            }
            String status = deploymentDetails.get("JobStatus").toString();
            if (status.equals("IN_PROGRESS")) {
                jobInProgress.countDown();
            } else if (jobInProgress.getCount() <= 0 && status.equals("SUCCEEDED")) {
                jobCompleted.countDown();
            }
        });

        // Inject our mocked local package store
        kernel.context.getv(DependencyResolver.class)
                .put(new DependencyResolver(new LocalPackageStore(LOCAL_CACHE_PATH), kernel));
        kernel.launch();

        // Create Job Doc
        String document = new ObjectMapper()
                .writeValueAsString(DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                        .deploymentId(UUID.randomUUID().toString()).rootPackages(Arrays.asList("CustomerApp"))
                        .deploymentPackageConfigurationList(Arrays.asList(
                                new DeploymentPackageConfiguration("CustomerApp", "1.0.0", null, null, null)
                                )).build());

        // Create job targeting our DUT
        String[] targets = {thing.thingArn};
        Utils.createJobWithId(document, targets, jobId);

        assertTrue(jobInProgress.await(5, TimeUnit.MINUTES));
        NetworkUtils networkUtils = NetworkUtils.getByPlatform();
        try {
            networkUtils.disconnect();

            // Wait for the deployment to finish offline
            assertTrue(jobCompleted.await(5, TimeUnit.MINUTES));
            // Leave connection broken for some more time
            Thread.sleep(10000);
        } finally {
            networkUtils.recover();
        }

        // Wait for DNS Cache expires
        Thread.sleep(DNS_CACHE_TTL.plus(Duration.ofSeconds(1)).toMillis());

        // Make sure the job hasn't been updated yet in cloud
        assertThat(Utils.iotClient.describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId)
                .thingName(thing.thingName).build()).execution().status(),
                anyOf(is(JobExecutionStatus.IN_PROGRESS), is(JobExecutionStatus.QUEUED)));

        // Wait up to 5 minutes for the IoT job to be updated
        Utils.waitForJobToComplete(jobId, Duration.ofMinutes(5));
        kernel.shutdown();

        // Make sure that IoT Job was marked as successful
        assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thing.thingName).build())
                .execution().status());
    }
}
