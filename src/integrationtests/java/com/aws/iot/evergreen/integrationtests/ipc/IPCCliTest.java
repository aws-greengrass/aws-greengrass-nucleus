/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentStatusKeeper;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.services.cli.Cli;
import com.aws.iot.evergreen.ipc.services.cli.CliImpl;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.ComponentNotFoundError;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.InvalidArgumentsError;
import com.aws.iot.evergreen.ipc.services.cli.models.CreateLocalDeploymentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.CreateLocalDeploymentResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.DeploymentStatus;
import com.aws.iot.evergreen.ipc.services.cli.models.GetComponentDetailsRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.GetComponentDetailsResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.ListComponentsResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.ListLocalDeploymentResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.RequestStatus;
import com.aws.iot.evergreen.ipc.services.cli.models.RestartComponentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.RestartComponentResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.StopComponentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.StopComponentResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.UpdateRecipesAndArtifactsRequest;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Slf4jLogAdapter;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.iot.evergreen.ipc.services.cli.models.LifecycleState.RUNNING;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
class IPCCliTest {

    private static Kernel kernel;
    private IPCClient client;

    @BeforeEach
    void beforeEach(ExtensionContext context) throws InterruptedException {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        kernel = prepareKernelFromConfigFile("ipc.yaml", TEST_SERVICE_NAME, this.getClass());
    }

    @AfterEach
    void afterEach() throws IOException {
        client.disconnect();
        kernel.shutdown();
    }

    @Test
    public void GIVEN_component_running_WHEN_get_component_request_made_THEN_service_details_sent() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response =
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("mqtt").build());
        assertNotNull(response);
        assertEquals("1.0.0", response.getComponentDetails().getVersion());
    }

    @Test
    public void GIVEN_get_component_request_made_WHEN_component_not_exist_THEN_error_sent(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        assertThrows(ComponentNotFoundError.class, ()->
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("unknown").build()));
    }

    @Test
    public void GIVEN_get_component_request_made_WHEN_empty_component_name_THEN_error_sent(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        assertThrows(InvalidArgumentsError.class, ()->
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("").build()));
    }

    @Test
    public void GIVEN_kernel_running_WHEN_list_component_request_made_THEN_components_details_sent() throws Exception {
        waitForServiceToComeInState("ServiceName", State.RUNNING).await(10, TimeUnit.SECONDS);
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        ListComponentsResponse response = cli.listComponents();
        assertNotNull(response);
        List<String> components =
                response.getComponents().stream().map(cd->cd.getComponentName()).collect(Collectors.toList());
        assertTrue(components.contains("mqtt"));
        assertTrue(components.contains("ServiceName"));
        assertFalse(components.contains("main"));
    }

    @Test
    public void GIVEN_kernel_running_WHEN_restart_component_request_made_THEN_components_details_sent() throws Exception {
        waitForServiceToComeInState("ServiceName", State.RUNNING).await(10, TimeUnit.SECONDS);
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceName").build());
        assertEquals(RUNNING, response.getComponentDetails().getState());
        RestartComponentResponse restartComponentResponse =
                cli.restartComponent(RestartComponentRequest.builder().componentName("ServiceName").build());
        assertEquals(RequestStatus.SUCCEEDED, restartComponentResponse.getRequestStatus());
        waitForServiceToComeInState("ServiceName", State.STARTING);
    }

    @Test
    public void GIVEN_kernel_running_WHEN_stop_component_request_made_THEN_components_details_sent() throws Exception {
        waitForServiceToComeInState("ServiceName", State.RUNNING).await(10, TimeUnit.SECONDS);
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceName").build());
        assertEquals(RUNNING, response.getComponentDetails().getState());

        StopComponentResponse stopComponentResponse =
                cli.stopComponent(StopComponentRequest.builder().componentName("ServiceName").build());
        assertEquals(RequestStatus.SUCCEEDED, stopComponentResponse.getRequestStatus());
        CountDownLatch stoppingLatch = waitForServiceToComeInState("ServiceName", State.STOPPING);
        // To verify get component details for the service in STOPPING state
        response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceName").build());
        stoppingLatch.await(10, TimeUnit.SECONDS);
    }

    @Test
    public void GIVEN_kernel_running_WHEN_create_deployment_after_recipe_update_THEN_kernel_runs_latest_recipe(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        waitForServiceToComeInState("ServiceName", State.RUNNING).await(10, TimeUnit.SECONDS);
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);

        // Deployment with updated recipes
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .recipeDirectoryPath(this.getClass().getResource("recipes").toURI().getPath())
                .build();
        cli.updateRecipesAndArtifacts(request);
        CreateLocalDeploymentRequest deploymentRequest = CreateLocalDeploymentRequest.builder()
                .rootComponentVersionsToAdd(Collections.singletonMap("ServiceName", "1.0.1"))
                .build();
        CreateLocalDeploymentResponse deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId1 = deploymentResponse.getDeploymentId();
        waitForServiceToComeInState("ServiceName", State.RUNNING).await(10, TimeUnit.SECONDS);

        waitForDeploymentToBeSuccessful(deploymentId1).await(30, TimeUnit.SECONDS);
        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceName").build());
        assertEquals("1.0.1", response.getComponentDetails().getVersion());

        // Deployment to remove a component
        deploymentRequest = CreateLocalDeploymentRequest.builder()
                .rootComponentsToRemove(Arrays.asList("ServiceName"))
                .build();
        deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId2 = deploymentResponse.getDeploymentId();
        waitForServiceToComeInState("ServiceName", State.FINISHED).await(10, TimeUnit.SECONDS);
        waitForDeploymentToBeSuccessful(deploymentId2).await(30, TimeUnit.SECONDS);
        ignoreExceptionOfType(context, ServiceLoadException.class);
        assertThrows(ComponentNotFoundError.class,
                ()->cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("ServiceName").build()));

        // List local deployments
        ListLocalDeploymentResponse localDeploymentResponse = cli.listLocalDeployments();
        List<String> localDeploymentIds =
                localDeploymentResponse.getLocalDeployments().stream().map(ld->ld.getDeploymentId())
                        .collect(Collectors.toList());
        assertThat(localDeploymentIds, containsInAnyOrder(deploymentId1, deploymentId2));
    }


    @Test
    public void GIVEN_kernel_running_WHEN_update_artifacts_and_deployment_THEN_kernel_copies_artifacts_correctly(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        waitForServiceToComeInState("ServiceName", State.RUNNING).await(10, TimeUnit.SECONDS);
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);

        // Deployment with updated recipes
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .recipeDirectoryPath(this.getClass().getResource("recipes").toURI().getPath())
                .artifactDirectoryPath(this.getClass().getResource("artifacts").toURI().getPath())
                .build();
        cli.updateRecipesAndArtifacts(request);
        assertTrue(Files.exists(kernel.getPackageStorePath().resolve(PackageStore.ARTIFACT_DIRECTORY)
                        .resolve("Component1").resolve("1.0.0").resolve("run.sh")));
        CreateLocalDeploymentRequest deploymentRequest = CreateLocalDeploymentRequest.builder()
                .groupName("NewGroup")
                .rootComponentVersionsToAdd(Collections.singletonMap("Component1", "1.0.0"))
                .build();
        CreateLocalDeploymentResponse deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId1 = deploymentResponse.getDeploymentId();
        waitForServiceToComeInState("Component1", State.RUNNING).await(10, TimeUnit.SECONDS);
        waitForDeploymentToBeSuccessful(deploymentId1).await(30, TimeUnit.SECONDS);
    }

    @Test
    public void GIVEN_kernel_running_WHEN_change_configuration_and_deployment_THEN_kernel_copies_artifacts_correctly(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        waitForServiceToComeInState("ServiceName", State.RUNNING).await(10, TimeUnit.SECONDS);
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);

        // Deployment with updated recipes
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .recipeDirectoryPath(this.getClass().getResource("recipes").toURI().getPath())
                .artifactDirectoryPath(this.getClass().getResource("artifacts").toURI().getPath())
                .build();
        cli.updateRecipesAndArtifacts(request);
        assertTrue(Files.exists(kernel.getPackageStorePath().resolve(PackageStore.ARTIFACT_DIRECTORY)
                .resolve("Component1").resolve("1.0.0").resolve("run.sh")));
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("Message", "NewWorld");
        Map<String, Map<String, Object>> componentToConfiguration = new HashMap<>();
        componentToConfiguration.put("Component1", configMap);
        CreateLocalDeploymentRequest deploymentRequest = CreateLocalDeploymentRequest.builder()
                .groupName("NewGroup")
                .componentToConfiguration(componentToConfiguration)
                .rootComponentVersionsToAdd(Collections.singletonMap("Component1", "1.0.0"))
                .build();
        CreateLocalDeploymentResponse deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId1 = deploymentResponse.getDeploymentId();
        CountDownLatch stdoutLatch = new CountDownLatch(1);
        Consumer<EvergreenStructuredLogMessage> logListener = m -> {
            if ("shell-runner-stdout".equals(m.getEventType())) {
                if(m.getMessage().contains("NewWorld")) {
                    stdoutLatch.countDown();
                }

            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);
        stdoutLatch.await(10, TimeUnit.SECONDS);
        waitForServiceToComeInState("Component1", State.RUNNING).await(10, TimeUnit.SECONDS);
        waitForDeploymentToBeSuccessful(deploymentId1).await(30, TimeUnit.SECONDS);

        //Get configuration in component details
        GetComponentDetailsResponse componentDetailsResponse =
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                        "Component1").build());
        assertEquals("NewWorld", componentDetailsResponse.getComponentDetails().getConfiguration().get("Message"));
    }


    private CountDownLatch waitForDeploymentToBeSuccessful(String deploymentId) {
        CountDownLatch deploymentLatch = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL,
                (deploymentDetails)->{
                    String receivedDeploymentId =
                            deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID).toString();
                    if (receivedDeploymentId.equals(deploymentId)) {
                        DeploymentStatus status = (DeploymentStatus) deploymentDetails
                                .get(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS);
                        if (status == DeploymentStatus.SUCCEEDED) {
                            deploymentLatch.countDown();
                        }
                    }
                    return true;
                }, IPCCliTest.class.getSimpleName());
        return deploymentLatch;
    }

    private CountDownLatch waitForServiceToComeInState(String serviceName, State state) throws InterruptedException {
        // wait for service to come up
        CountDownLatch awaitServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(serviceName) && newState.equals(state)) {
                awaitServiceLatch.countDown();
            }
        });
        return awaitServiceLatch;
    }
}
