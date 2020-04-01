/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.integrationtests.e2e.util.Utils;
import com.aws.iot.evergreen.kernel.EvergreenService;
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
import software.amazon.awssdk.services.iot.model.DescribeJobRequest;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;
import software.amazon.awssdk.services.iot.model.JobStatus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_MQTT_CLIENT_ENDPOINT;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("E2E")
public class DeploymentE2ETest {
    @TempDir
    static Path tempRootDir;

    private static String rootCaFilePath;
    private static String privateKeyFilePath;
    private static String certificateFilePath;
    private static final Path LOCAL_CACHE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    @BeforeAll
    static void beforeAll() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        rootCaFilePath = tempRootDir.resolve("rootCA.pem").toString();
        privateKeyFilePath = tempRootDir.resolve("privKey.key").toString();
        certificateFilePath = tempRootDir.resolve("thingCert.crt").toString();
    }

    @AfterAll
    static void afterAll() {
        // Cleanup all IoT thing resources we created
        Utils.cleanAllCreatedThings();
        Utils.cleanAllCreatedJobs();
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deploy_new_services_e2e_THEN_new_services_deployed_and_job_is_successful()
            throws Exception {
        // Setup IoT resources
        Utils.downloadRootCAToFile(new File(rootCaFilePath));
        Utils.ThingInfo thing = Utils.createThing();
        try (CommitableFile cf = CommitableFile.of(new File(privateKeyFilePath).toPath(), true)) {
            cf.write(thing.keyPair.privateKey().getBytes(StandardCharsets.UTF_8));
        }
        try (CommitableFile cf = CommitableFile.of(new File(certificateFilePath).toPath(), true)) {
            cf.write(thing.certificatePem.getBytes(StandardCharsets.UTF_8));
        }

        // Inject IoT resources into kernel
        Kernel kernel = new Kernel().parseArgs("-i", getClass().getResource("blank_config.yaml").toString());
        Topics deploymentServiceTopics = kernel.lookupTopics(SERVICES_NAMESPACE_TOPIC, "DeploymentService");
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_THING_NAME).setValue(thing.thingName);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_MQTT_CLIENT_ENDPOINT).setValue(thing.endpoint);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_PRIVATE_KEY_PATH).setValue(privateKeyFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_CERTIFICATE_FILE_PATH).setValue(certificateFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_ROOT_CA_PATH).setValue(rootCaFilePath);

        // Inject our mocked local package store
        kernel.context.getv(DependencyResolver.class)
                .put(new DependencyResolver(new LocalPackageStore(LOCAL_CACHE_PATH), kernel));
        kernel.launch();

        // Create Job Doc
        String document = new ObjectMapper().writeValueAsString(
                DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                        .deploymentId(UUID.randomUUID().toString()).rootPackages(Arrays.asList("CustomerApp"))
                        .deploymentPackageConfigurationList(Arrays.asList(
                                new DeploymentPackageConfiguration("CustomerApp", "1.0.0", null, null, null))).build());

        // Create job targeting our DUT
        // TODO: Eventually switch this to target using Thing Group instead of individual Thing
        String[] targets = new String[]{thing.thingArn};
        String jobId = Utils.createJob(document, targets);

        // Wait up to 5 minutes for the job to complete
        Utils.waitForJobToComplete(jobId, Duration.ofMinutes(5));
        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertEquals(kernel.getMain().getState(), State.FINISHED);
        assertEquals(State.FINISHED, EvergreenService.locate(kernel.context, "CustomerApp").getState());
        kernel.shutdownNow();

        // Make sure that IoT Job was marked as successful
        assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thing.thingName).build()).execution()
                .status());
        assertEquals(JobStatus.COMPLETED,
                Utils.iotClient.describeJob(DescribeJobRequest.builder().jobId(jobId).build()).job().status());
    }
}
