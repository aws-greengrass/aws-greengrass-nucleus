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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.iot.evergreen.ipc.modules.CLIService.CLI_AUTH_TOKEN;
import static com.aws.iot.evergreen.ipc.modules.CLIService.CLI_IPC_INFO_FILENAME;
import static com.aws.iot.evergreen.ipc.modules.CLIService.CLI_SERVICE;
import static com.aws.iot.evergreen.ipc.modules.CLIService.SOCKET_URL;
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
    private static int LOCAL_DEPLOYMENT_TIMEOUT_MINUTES = 5;
    private static int SERVICE_STATE_CHECK_TIMEOUT_MINUTES = 5;
    private IPCClient client;
    private static ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @BeforeEach
    void beforeEach(ExtensionContext context) throws InterruptedException, IOException {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        kernel = prepareKernelFromConfigFile("ipc.yaml", TEST_SERVICE_NAME, this.getClass());
        //wait for CLI Service to be up and running
        CountDownLatch cliServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((s, o, n)->{
            if (s.getName().equals(CLI_SERVICE) && n == State.RUNNING) {
                cliServiceLatch.countDown();
            }
        });
        cliServiceLatch.countDown();
    }

    @AfterEach
    void afterEach() throws IOException {
        if (client != null) {
            client.disconnect();
        }
        kernel.shutdown();
    }

    @Test
    public void GIVEN_component_running_WHEN_get_component_request_made_THEN_service_details_sent() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response =
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("mqtt").build());
        assertNotNull(response);
        assertEquals("1.0.0", response.getComponentDetails().getVersion());
    }

    private KernelIPCClientConfig getIPCConfigForCli() throws IOException, URISyntaxException {
        Path filepath = kernel.getRootPath().resolve(CLI_IPC_INFO_FILENAME);
        Map<String, String> ipcInfo = OBJECT_MAPPER.readValue(Files.readAllBytes(filepath), Map.class);
        URI serverUri = new URI(ipcInfo.get(SOCKET_URL));
        int port = serverUri.getPort();
        String address = serverUri.getHost();
        String token = ipcInfo.get(CLI_AUTH_TOKEN);
        return KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token(token).build();
    }

    @Test
    public void GIVEN_get_component_request_made_WHEN_component_not_exist_THEN_error_sent(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        assertThrows(ComponentNotFoundError.class, ()->
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("unknown").build()));
    }

    @Test
    public void GIVEN_get_component_request_made_WHEN_empty_component_name_THEN_error_sent(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        assertThrows(InvalidArgumentsError.class, ()->
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("").build()));
    }

    @Test
    public void GIVEN_kernel_running_WHEN_list_component_request_made_THEN_components_details_sent() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForCli();
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
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);

        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceName").build());
        assertEquals(RUNNING, response.getComponentDetails().getState());
        CountDownLatch serviceLatch = waitForServiceToComeInState("ServiceName", State.STARTING);
        RestartComponentResponse restartComponentResponse =
                cli.restartComponent(RestartComponentRequest.builder().componentName("ServiceName").build());
        assertEquals(RequestStatus.SUCCEEDED, restartComponentResponse.getRequestStatus());
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_MINUTES, TimeUnit.MINUTES));
    }

    @Test
    public void GIVEN_kernel_running_WHEN_stop_component_request_made_THEN_components_details_sent() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceName").build());
        assertEquals(RUNNING, response.getComponentDetails().getState());

        CountDownLatch stoppingLatch = waitForServiceToComeInState("ServiceName", State.STOPPING);
        StopComponentResponse stopComponentResponse =
                cli.stopComponent(StopComponentRequest.builder().componentName("ServiceName").build());
        assertEquals(RequestStatus.SUCCEEDED, stopComponentResponse.getRequestStatus());
        assertTrue(stoppingLatch.await(SERVICE_STATE_CHECK_TIMEOUT_MINUTES, TimeUnit.MINUTES));
        // To verify get component details for the service in STOPPING state
        response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceName").build());
    }

    @Test
    public void GIVEN_kernel_running_WHEN_create_deployment_after_recipe_update_THEN_kernel_runs_latest_recipe(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);

        // Deployment with updated recipes
        Path recipesPath = Paths.get(this.getClass().getResource("recipes").toURI());
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .recipeDirectoryPath(recipesPath.toString())
                .build();
        cli.updateRecipesAndArtifacts(request);
        CreateLocalDeploymentRequest deploymentRequest = CreateLocalDeploymentRequest.builder()
                .rootComponentVersionsToAdd(Collections.singletonMap("ServiceName", "1.0.1"))
                .build();
        CountDownLatch serviceLatch = waitForServiceToComeInState("ServiceName", State.RUNNING);
        CreateLocalDeploymentResponse deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId1 = deploymentResponse.getDeploymentId();
        CountDownLatch deploymentLatch = waitForDeploymentToBeSuccessful(deploymentId1);
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_MINUTES, TimeUnit.MINUTES));
        assertTrue(deploymentLatch.await(LOCAL_DEPLOYMENT_TIMEOUT_MINUTES, TimeUnit.MINUTES));

        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceName").build());
        assertEquals("1.0.1", response.getComponentDetails().getVersion());

        // Deployment to remove a component
        deploymentRequest = CreateLocalDeploymentRequest.builder()
                .rootComponentsToRemove(Arrays.asList("ServiceName"))
                .build();
        serviceLatch = waitForServiceToComeInState("ServiceName", State.FINISHED);
        deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId2 = deploymentResponse.getDeploymentId();
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_MINUTES, TimeUnit.MINUTES));
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
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);

        // Deployment with updated recipes
        Path recipesPath = Paths.get(this.getClass().getResource("recipes").toURI());
        Path artifactsPath = Paths.get(this.getClass().getResource("artifacts").toURI());
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .recipeDirectoryPath(recipesPath.toString())
                .artifactDirectoryPath(artifactsPath.toString())
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
        assertTrue(waitForServiceToComeInState("Component1", State.RUNNING)
                .await(SERVICE_STATE_CHECK_TIMEOUT_MINUTES, TimeUnit.MINUTES));
        assertTrue(waitForDeploymentToBeSuccessful(deploymentId1).await(LOCAL_DEPLOYMENT_TIMEOUT_MINUTES,
                TimeUnit.MINUTES));
    }

    @Test
    public void GIVEN_kernel_running_WHEN_change_configuration_and_deployment_THEN_kernel_copies_artifacts_correctly(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);

        // Deployment with updated recipes
        Path recipesPath = Paths.get(this.getClass().getResource("recipes").toURI());
        Path artifactsPath = Paths.get(this.getClass().getResource("artifacts").toURI());
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .recipeDirectoryPath(recipesPath.toString())
                .artifactDirectoryPath(artifactsPath.toString())
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
        CountDownLatch serviceLatch = waitForServiceToComeInState("Component1", State.RUNNING);
        CountDownLatch stdoutLatch = new CountDownLatch(1);
        Consumer<EvergreenStructuredLogMessage> logListener = m -> {
            if ("shell-runner-stdout".equals(m.getEventType())) {
                if(m.getContexts().containsKey("stdout") && m.getContexts().get("stdout").contains("NewWorld")) {
                    stdoutLatch.countDown();
                }

            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);
        CreateLocalDeploymentResponse deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId1 = deploymentResponse.getDeploymentId();
        CountDownLatch deploymentLatch = waitForDeploymentToBeSuccessful(deploymentId1);

        assertTrue(deploymentLatch.await(LOCAL_DEPLOYMENT_TIMEOUT_MINUTES, TimeUnit.MINUTES));
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_MINUTES, TimeUnit.MINUTES));
        assertTrue(stdoutLatch.await(10, TimeUnit.SECONDS));

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
