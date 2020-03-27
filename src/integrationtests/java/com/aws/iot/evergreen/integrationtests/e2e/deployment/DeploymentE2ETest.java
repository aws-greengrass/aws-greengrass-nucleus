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
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.iot.evergreen.deployment.DeploymentService.DEVICE_PARAM_MQTT_CLIENT_ENDPOINT;
import static com.aws.iot.evergreen.deployment.DeploymentService.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.iot.evergreen.deployment.DeploymentService.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.iot.evergreen.deployment.DeploymentService.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("E2E")
public class DeploymentE2ETest {
    @TempDir
    static Path tempRootDir;

    private static String rootCaFilePath;
    private static String privateKeyFilePath;
    private static String certificateFilePath;
    private static final Path LOCAL_CACHE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    private static Kernel kernel;
    private static Utils.ThingInfo thing;

    @BeforeAll
    static void beforeAll() throws IOException {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        rootCaFilePath = tempRootDir.resolve("rootCA.pem").toString();
        privateKeyFilePath = tempRootDir.resolve("privKey.key").toString();
        certificateFilePath = tempRootDir.resolve("thingCert.crt").toString();

        kernel = new Kernel().parseArgs("-i", DeploymentE2ETest.class.getResource("blank_config.yaml").toString());
        setupIotResourcesAndInjectIntoKernel();
        injectKernelPackageManagementDependencies();
        kernel.launch();
    }

    @AfterAll
    static void afterAll() {
        kernel.shutdownNow();
        // Cleanup all IoT thing resources we created
        Utils.cleanAllCreatedThings();
        Utils.cleanAllCreatedJobs();
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deploy_new_services_e2e_THEN_new_services_deployed_and_job_is_successful()
            throws Exception {
        // Create Job Doc
        String document = new ObjectMapper().writeValueAsString(
                DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                        .deploymentId(UUID.randomUUID().toString()).rootPackages(Arrays.asList("CustomerApp"))
                        .deploymentPackageConfigurationList(Arrays.asList(
                                new DeploymentPackageConfiguration("CustomerApp", "1.0.0", null, null, null))).build());

        // Create job targeting our DUT
        // TODO: Eventually switch this to target using Thing Group instead of individual Thing
        String[] targets = {thing.thingArn};
        String jobId = Utils.createJob(document, targets);

        // Wait up to 5 minutes for the job to complete
        Utils.waitForJobToComplete(jobId, Duration.ofMinutes(5));
        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertEquals(State.FINISHED, kernel.getMain().getState());
        assertEquals(State.FINISHED, EvergreenService.locate(kernel.context, "CustomerApp").getState());
        kernel.shutdown();

        // Make sure that IoT Job was marked as successful
        assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thing.thingName).build()).execution()
                .status());
        assertEquals(JobStatus.COMPLETED,
                Utils.iotClient.describeJob(DescribeJobRequest.builder().jobId(jobId).build()).job().status());
    }

    @Test
    void GIVEN_kernel_running_with_deployed_services_WHEN_deployment_removes_packages_THEN_services_should_be_stopped_and_job_is_successful()
            throws Exception {
        // Target our DUT for deployments
        // TODO: Eventually switch this to target using Thing Group instead of individual Thing
        String[] targets = new String[]{thing.thingArn};

        // First Deployment to have some services running in Kernel which can be removed later
        String document1 = new ObjectMapper().writeValueAsString(
                DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                        .deploymentId(UUID.randomUUID().toString())
                        .rootPackages(Arrays.asList("CustomerApp", "SomeService")).deploymentPackageConfigurationList(
                        Arrays.asList(new DeploymentPackageConfiguration("CustomerApp", "1.0.0", null, null, null),
                                new DeploymentPackageConfiguration("SomeService", "1.0.0", null, null, null))).build());
        String jobId1 = Utils.createJob(document1, targets);
        Utils.waitForJobToComplete(jobId1, Duration.ofMinutes(5));

        // Second deployment to remove some services deployed previously
        String document2 = new ObjectMapper().writeValueAsString(
                DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                        .deploymentId(UUID.randomUUID().toString()).rootPackages(Arrays.asList("CustomerApp"))
                        .deploymentPackageConfigurationList(Arrays.asList(
                                new DeploymentPackageConfiguration("CustomerApp", "1.0.0", null, null, null))).build());
        String jobId2 = Utils.createJob(document2, targets);
        Utils.waitForJobToComplete(jobId2, Duration.ofMinutes(5));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertEquals(State.FINISHED, kernel.getMain().getState());
        assertEquals(State.FINISHED, EvergreenService.locate(kernel.context, "CustomerApp").getState());
        assertThrows(ServiceLoadException.class, () -> {
            EvergreenService.locate(kernel.context, "SomeService").getState();
        });

        // Make sure that IoT Job was marked as successful
        assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId2).thingName(thing.thingName).build()).execution()
                .status());
        assertEquals(JobStatus.COMPLETED,
                Utils.iotClient.describeJob(DescribeJobRequest.builder().jobId(jobId2).build()).job().status());
    }

    private static void setupIotResourcesAndInjectIntoKernel() throws IOException {
        Utils.downloadRootCAToFile(new File(rootCaFilePath));
        thing = Utils.createThing();
        try (CommitableFile cf = CommitableFile.of(new File(privateKeyFilePath).toPath(), true)) {
            cf.write(thing.keyPair.privateKey().getBytes(StandardCharsets.UTF_8));
        }
        try (CommitableFile cf = CommitableFile.of(new File(certificateFilePath).toPath(), true)) {
            cf.write(thing.certificatePem.getBytes(StandardCharsets.UTF_8));
        }

        Topics deploymentServiceTopics = kernel.lookupTopics(SERVICES_NAMESPACE_TOPIC, "DeploymentService");
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_THING_NAME).setValue(thing.thingName);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_MQTT_CLIENT_ENDPOINT).setValue(thing.endpoint);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_PRIVATE_KEY_PATH).setValue(privateKeyFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_CERTIFICATE_FILE_PATH).setValue(certificateFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_ROOT_CA_PATH).setValue(rootCaFilePath);
    }

    private static void injectKernelPackageManagementDependencies() {
        kernel.context.getv(DependencyResolver.class)
                .put(new DependencyResolver(new LocalPackageStore(LOCAL_CACHE_PATH), kernel));
    }
}
