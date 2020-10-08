/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.ipc.IPCClient;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.exceptions.IPCClientException;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.ipc.services.cli.Cli;
import com.aws.greengrass.ipc.services.cli.CliImpl;
import com.aws.greengrass.ipc.services.cli.exceptions.ComponentNotFoundError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidArgumentsError;
import com.aws.greengrass.ipc.services.cli.models.CreateLocalDeploymentRequest;
import com.aws.greengrass.ipc.services.cli.models.CreateLocalDeploymentResponse;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsRequest;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsResponse;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusRequest;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusResponse;
import com.aws.greengrass.ipc.services.cli.models.ListComponentsResponse;
import com.aws.greengrass.ipc.services.cli.models.ListLocalDeploymentResponse;
import com.aws.greengrass.ipc.services.cli.models.RequestStatus;
import com.aws.greengrass.ipc.services.cli.models.RestartComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.RestartComponentResponse;
import com.aws.greengrass.ipc.services.cli.models.StopComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.StopComponentResponse;
import com.aws.greengrass.ipc.services.cli.models.UpdateRecipesAndArtifactsRequest;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getListenerForServiceRunning;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.waitForDeploymentToBeSuccessful;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.waitForServiceToComeInState;
import static com.aws.greengrass.ipc.modules.CLIService.CLI_AUTH_TOKEN;
import static com.aws.greengrass.ipc.modules.CLIService.CLI_SERVICE;
import static com.aws.greengrass.ipc.modules.CLIService.SOCKET_URL;
import static com.aws.greengrass.ipc.modules.CLIService.posixGroups;
import static com.aws.greengrass.ipc.services.cli.models.LifecycleState.RUNNING;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(GGExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IPCCliTest {

    private static Kernel kernel;
    private static final int LOCAL_DEPLOYMENT_TIMEOUT_SECONDS = 15;
    private static final int SERVICE_STATE_CHECK_TIMEOUT_SECONDS = 15;
    private IPCClient client;
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @BeforeAll
    static void beforeAll() throws InterruptedException, ServiceLoadException {
        kernel = prepareKernelFromConfigFile("ipc.yaml", IPCCliTest.class, CLI_SERVICE, TEST_SERVICE_NAME);
        BaseITCase.setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);
    }

    @AfterAll
    static void afterAll() {
        kernel.shutdown();
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) {
        ignoreExceptionOfType(context, ConnectException.class);
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");

    }

    @AfterEach
    void afterEach() throws IOException {
        if (client != null) {
            client.disconnect();
        }
    }

    @Test
    @Order(1)
    void GIVEN_component_running_WHEN_get_component_request_made_THEN_service_details_sent() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response =
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("mqtt").build());
        assertNotNull(response);
        assertEquals("1.0.0", response.getComponentDetails().getVersion());
    }

    @Test
    @Order(2)
    void GIVEN_get_component_request_made_WHEN_component_not_exist_THEN_error_sent(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        assertThrows(ComponentNotFoundError.class, ()->
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("unknown").build()));
    }

    @Test
    @Order(3)
    void GIVEN_get_component_request_made_WHEN_empty_component_name_THEN_error_sent(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        assertThrows(InvalidArgumentsError.class, ()->
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("").build()));
    }

    @Test
    @Order(4)
    void GIVEN_kernel_running_WHEN_list_component_request_made_THEN_components_details_sent() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        ListComponentsResponse response = cli.listComponents();
        assertNotNull(response);
        List<String> components =
                response.getComponents().stream().map(cd->cd.getComponentName()).collect(Collectors.toList());
        assertTrue(components.contains("mqtt"));
        assertTrue(components.contains(TEST_SERVICE_NAME));
        assertFalse(components.contains("main"));
    }

    @Test
    @Order(5)
    void GIVEN_kernel_running_WHEN_restart_component_request_made_THEN_components_restarts() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceToBeRestarted").build());
        assertEquals(RUNNING, response.getComponentDetails().getState());
        CountDownLatch serviceLatch = waitForServiceToComeInState("ServiceToBeRestarted", State.STARTING, kernel);
        RestartComponentResponse restartComponentResponse =
                cli.restartComponent(RestartComponentRequest.builder().componentName("ServiceToBeRestarted").build());
        assertEquals(RequestStatus.SUCCEEDED, restartComponentResponse.getRequestStatus());
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @Order(6)
    void GIVEN_kernel_running_WHEN_stop_component_request_made_THEN_components_stops() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForCli();
        client = new IPCClientImpl(config);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                "ServiceToBeStopped").build());
        assertEquals(RUNNING, response.getComponentDetails().getState());

        CountDownLatch stoppingLatch = waitForServiceToComeInState("ServiceToBeStopped", State.STOPPING, kernel);
        StopComponentResponse stopComponentResponse =
                cli.stopComponent(StopComponentRequest.builder().componentName("ServiceToBeStopped").build());
        assertEquals(RequestStatus.SUCCEEDED, stopComponentResponse.getRequestStatus());
        assertTrue(stoppingLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @Order(7)
    void GIVEN_kernel_running_WHEN_create_deployment_after_recipe_update_THEN_kernel_runs_latest_recipe(ExtensionContext context)
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
                .rootComponentVersionsToAdd(Collections.singletonMap(TEST_SERVICE_NAME, "1.0.1"))
                .build();
        CountDownLatch serviceLatch = waitForServiceToComeInState(TEST_SERVICE_NAME, State.RUNNING, kernel);
        CreateLocalDeploymentResponse deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId1 = deploymentResponse.getDeploymentId();
        CountDownLatch deploymentLatch = waitForDeploymentToBeSuccessful(deploymentId1, kernel);
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(deploymentLatch.await(LOCAL_DEPLOYMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        GetComponentDetailsResponse response = cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                TEST_SERVICE_NAME).build());
        assertEquals("1.0.1", response.getComponentDetails().getVersion());

        // Deployment to remove a component
        deploymentRequest = CreateLocalDeploymentRequest.builder()
                .rootComponentsToRemove(Arrays.asList(TEST_SERVICE_NAME))
                .build();
        serviceLatch = waitForServiceToComeInState(TEST_SERVICE_NAME, State.FINISHED, kernel);
        deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId2 = deploymentResponse.getDeploymentId();
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        ignoreExceptionOfType(context, ServiceLoadException.class);
        eventuallySuccessfulDeployment(cli, deploymentId2, 60);
        assertThrows(ComponentNotFoundError.class,
                ()->cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(TEST_SERVICE_NAME).build()));

        // List local deployments
        ListLocalDeploymentResponse localDeploymentResponse = cli.listLocalDeployments();
        List<String> localDeploymentIds =
                localDeploymentResponse.getLocalDeployments().stream().map(ld->ld.getDeploymentId())
                        .collect(Collectors.toList());
        assertThat(localDeploymentIds, containsInAnyOrder(deploymentId1, deploymentId2));
    }

    @Test
    @Order(8)
    void GIVEN_kernel_running_WHEN_update_artifacts_and_deployment_THEN_kernel_copies_artifacts_correctly(ExtensionContext context)
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
        assertTrue(Files.exists(kernel.getNucleusPaths().componentStorePath().resolve(ComponentStore.ARTIFACT_DIRECTORY)
                .resolve("Component1").resolve("1.0.0").resolve("run.sh")));
        CreateLocalDeploymentRequest deploymentRequest = CreateLocalDeploymentRequest.builder()
                .groupName("NewGroup")
                .rootComponentVersionsToAdd(Collections.singletonMap("Component1", "1.0.0"))
                .build();

        CreateLocalDeploymentResponse deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId1 = deploymentResponse.getDeploymentId();
        CountDownLatch waitForComponent1ToRun = waitForServiceToComeInState("Component1", State.RUNNING, kernel);
        CountDownLatch waitFordeploymentId1 = waitForDeploymentToBeSuccessful(deploymentId1, kernel);
        assertTrue(waitForComponent1ToRun.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(waitFordeploymentId1.await(LOCAL_DEPLOYMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @Order(9)
    void GIVEN_kernel_running_WHEN_change_configuration_and_deployment_THEN_kernel_copies_artifacts_correctly(ExtensionContext context)
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
        assertTrue(Files.exists(kernel.getNucleusPaths().componentStorePath().resolve(ComponentStore.ARTIFACT_DIRECTORY)
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
        CountDownLatch serviceLatch = waitForServiceToComeInState("Component1", State.RUNNING, kernel);
        CountDownLatch stdoutLatch = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> logListener = m -> {
            if ("shell-runner-stdout".equals(m.getEventType())) {
                if(m.getContexts().containsKey("stdout") && m.getContexts().get("stdout").contains("NewWorld")) {
                    stdoutLatch.countDown();
                }
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);
        CreateLocalDeploymentResponse deploymentResponse = cli.createLocalDeployment(deploymentRequest);
        String deploymentId1 = deploymentResponse.getDeploymentId();
        CountDownLatch deploymentLatch = waitForDeploymentToBeSuccessful(deploymentId1, kernel);

        assertTrue(deploymentLatch.await(LOCAL_DEPLOYMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(stdoutLatch.await(10, TimeUnit.SECONDS));

        //Get configuration in component details
        GetComponentDetailsResponse componentDetailsResponse =
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName(
                        "Component1").build());
        assertEquals("NewWorld", componentDetailsResponse.getComponentDetails().getConfiguration().get("Message"));
    }

    @Test
    @Order(10)
    void GIVEN_kernel_running_WHEN_CLI_authorized_groups_updated_THEN_old_token_revoked_and_new_token_accepted(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, UnauthenticatedException.class);
        KernelIPCClientConfig defaultConfig = getIPCConfigForCli();

        CountDownLatch awaitIpcServiceLatch = new CountDownLatch(1);
        GlobalStateChangeListener listener = getListenerForServiceRunning(awaitIpcServiceLatch, CLI_SERVICE);
        kernel.getContext().addGlobalStateChangeListener(listener);

        String validGid;
        if (Exec.isWindows) {
            // TODO support windows
            validGid = "0";
        } else {
            validGid = selectAValidGid();
        }
        assertNotNull(validGid, "Failed to find a single valid GID on this test instance");
        kernel.locate(CLI_SERVICE).getConfig().lookup(PARAMETERS_CONFIG_KEY, posixGroups).withValue(validGid);
        assertTrue(awaitIpcServiceLatch.await(10, TimeUnit.SECONDS));
        kernel.getContext().removeGlobalStateChangeListener(listener);

        KernelIPCClientConfig updatedConfig = getIPCConfigForCli();

        // THEN new client with outdate token will fail to authenticate
        assertThrows(IPCClientException.class, () -> new IPCClientImpl(defaultConfig));
        // AND new client with correct token will be accepted
        client = new IPCClientImpl(updatedConfig);
        Cli cli = new CliImpl(client);
        GetComponentDetailsResponse response =
                cli.getComponentDetails(GetComponentDetailsRequest.builder().componentName("Component1").build());
        assertNotNull(response);
        assertEquals("1.0.0", response.getComponentDetails().getVersion());
    }

    private KernelIPCClientConfig getIPCConfigForCli () throws IOException, URISyntaxException {
        File[] authFiles = kernel.getNucleusPaths().cliIpcInfoPath().toFile().listFiles();
        assertEquals(1, authFiles.length);

        Map<String, String> ipcInfo = OBJECT_MAPPER.readValue(Files.readAllBytes(authFiles[0].toPath()), Map.class);
        URI serverUri = new URI(ipcInfo.get(SOCKET_URL));
        int port = serverUri.getPort();
        String address = serverUri.getHost();
        String token = ipcInfo.get(CLI_AUTH_TOKEN);
        return KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token(token).build();
    }

    private void eventuallySuccessfulDeployment(Cli cli, String deploymentId, int timeoutInSeconds) throws Exception {
        LocalTime startTime = LocalTime.now();
        while (LocalTime.now().isBefore(startTime.plusSeconds(timeoutInSeconds))) {
            GetLocalDeploymentStatusResponse response =
                    cli.getLocalDeploymentStatus(GetLocalDeploymentStatusRequest.builder().deploymentId(deploymentId).build());
            if (response.getDeployment().getStatus() == DeploymentStatus.SUCCEEDED) {
                return;
            }
            Thread.sleep(1000);
        }
        fail(String.format("Deployment %s not successful in given time %d seconds", deploymentId, timeoutInSeconds));
    }

    private String selectAValidGid() throws IOException, InterruptedException {
        String groups = Exec.cmd("groups");
        for (String group : groups.split(" ")) {
            long gid;
            try {
                gid = Platform.getInstance().getGroup(group).getId();
                return Long.toString(gid);
            } catch (IOException | NumberFormatException ignore) {
            }
        }
        return null;
    }
}
