/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.testcommons.testutilities.UniqueRootPathExtension;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.unix.UnixPlatform;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ComponentDetails;
import software.amazon.awssdk.aws.greengrass.model.CreateLocalDeploymentRequest;
import software.amazon.awssdk.aws.greengrass.model.CreateLocalDeploymentResponse;
import software.amazon.awssdk.aws.greengrass.model.GetComponentDetailsRequest;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.LifecycleState;
import software.amazon.awssdk.aws.greengrass.model.ListComponentsRequest;
import software.amazon.awssdk.aws.greengrass.model.ListComponentsResponse;
import software.amazon.awssdk.aws.greengrass.model.ListLocalDeploymentsRequest;
import software.amazon.awssdk.aws.greengrass.model.ListLocalDeploymentsResponse;
import software.amazon.awssdk.aws.greengrass.model.ResourceNotFoundError;
import software.amazon.awssdk.aws.greengrass.model.RestartComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateRecipesAndArtifactsRequest;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.model.AccessDeniedException;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getEventStreamRpcConnection;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getListenerForServiceRunning;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.waitForDeploymentToBeSuccessful;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.waitForServiceToComeInState;
import static com.aws.greengrass.ipc.modules.CLIService.CLI_AUTH_TOKEN;
import static com.aws.greengrass.ipc.modules.CLIService.CLI_SERVICE;
import static com.aws.greengrass.ipc.modules.CLIService.posixGroups;
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

@ExtendWith({GGExtension.class, UniqueRootPathExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisabledOnOs(OS.WINDOWS)
class IPCCliTest {

    private static final int LOCAL_DEPLOYMENT_TIMEOUT_SECONDS = 15;
    private static final int SERVICE_STATE_CHECK_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_TIMEOUT_IN_SEC = 5;
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static Kernel kernel;
    private static GreengrassCoreIPCClient clientConnection;
    private static EventStreamRPCConnection eventStreamRpcConnection;

    @BeforeAll
    static void beforeAll() throws Exception {
        kernel = prepareKernelFromConfigFile("ipc.yaml", IPCCliTest.class, CLI_SERVICE, TEST_SERVICE_NAME);
        BaseITCase.setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);
        eventStreamRpcConnection = getEventStreamRpcConnection(kernel, CLI_SERVICE);
        clientConnection = new GreengrassCoreIPCClient(eventStreamRpcConnection);
    }

    @AfterAll
    static void afterAll() {
        if (eventStreamRpcConnection != null) {
            eventStreamRpcConnection.disconnect();
        }
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) {
        ignoreExceptionOfType(context, ConnectException.class);
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");

    }

    @Test
    @Order(1)
    void GIVEN_component_running_WHEN_get_component_request_made_THEN_service_details_sent() throws Exception {

        GetComponentDetailsRequest request = new GetComponentDetailsRequest();
        request.setComponentName("mqtt");
        ComponentDetails componentDetails = clientConnection.getComponentDetails(request, Optional.empty())
                .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS).getComponentDetails();

        assertNotNull(componentDetails);
        assertEquals("1.0.0", componentDetails.getVersion());
    }

    @Test
    @Order(2)
    void GIVEN_get_component_request_made_WHEN_component_not_exist_THEN_error_sent(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        GetComponentDetailsRequest request = new GetComponentDetailsRequest();
        request.setComponentName("unknown");

        ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                clientConnection.getComponentDetails(request, Optional.empty())
                        .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS).getComponentDetails());

        assertEquals(ResourceNotFoundError.class, executionException.getCause().getClass());
    }

    @Test
    @Order(3)
    void GIVEN_get_component_request_made_WHEN_empty_component_name_THEN_error_sent(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        GetComponentDetailsRequest request = new GetComponentDetailsRequest();
        request.setComponentName("");
        ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                clientConnection.getComponentDetails(request, Optional.empty())
                        .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS).getComponentDetails());

        assertEquals(InvalidArgumentsError.class, executionException.getCause().getClass());
    }

    @Test
    @Order(4)
    void GIVEN_kernel_running_WHEN_list_component_request_made_THEN_components_details_sent() throws Exception {
        ListComponentsRequest request = new ListComponentsRequest();
        ListComponentsResponse listComponentsResponse =
                clientConnection.listComponents(request, Optional.empty()).getResponse()
                        .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);
        List<String> components =
                listComponentsResponse.getComponents().stream().map(cd -> cd.getComponentName()).collect(Collectors.toList());
        assertTrue(components.contains("mqtt"));
        assertTrue(components.contains(TEST_SERVICE_NAME));
        assertFalse(components.contains("main"));

    }

    @Test
    @Order(5)
    void GIVEN_kernel_running_WHEN_restart_component_request_made_THEN_components_restarts() throws Exception {

        GetComponentDetailsRequest request = new GetComponentDetailsRequest();
        request.setComponentName("ServiceToBeRestarted");
        ComponentDetails componentDetails = clientConnection.getComponentDetails(request, Optional.empty())
                .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS).getComponentDetails();

        assertEquals(LifecycleState.RUNNING, componentDetails.getState());


        CountDownLatch serviceLatch = waitForServiceToComeInState("ServiceToBeRestarted", State.STARTING, kernel);
        RestartComponentRequest restartRequest = new RestartComponentRequest();
        restartRequest.setComponentName("ServiceToBeRestarted");
        clientConnection.restartComponent(restartRequest, Optional.empty()).getResponse()
                .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @Order(6)
    void GIVEN_kernel_running_WHEN_stop_component_request_made_THEN_components_stops() throws Exception {
        GetComponentDetailsRequest request = new GetComponentDetailsRequest();
        request.setComponentName("ServiceToBeStopped");
        ComponentDetails componentDetails = clientConnection.getComponentDetails(request, Optional.empty())
                .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS).getComponentDetails();

        assertEquals(LifecycleState.RUNNING, componentDetails.getState());

        CountDownLatch stoppingLatch = waitForServiceToComeInState("ServiceToBeStopped", State.STOPPING, kernel);
        software.amazon.awssdk.aws.greengrass.model.StopComponentRequest stopRequest = new software.amazon.awssdk.aws.greengrass.model.StopComponentRequest();
        stopRequest.setComponentName("ServiceToBeStopped");
        clientConnection.stopComponent(stopRequest, Optional.empty()).getResponse()
                .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);
        assertTrue(stoppingLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @Order(7)
    void GIVEN_kernel_running_WHEN_create_deployment_after_recipe_update_THEN_kernel_runs_latest_recipe(ExtensionContext context)
            throws Exception {

        // updated recipes
        Path recipesPath = Paths.get(this.getClass().getResource("recipes").toURI());
        UpdateRecipesAndArtifactsRequest request = new UpdateRecipesAndArtifactsRequest();
        request.setRecipeDirectoryPath(recipesPath.toString());
        clientConnection.updateRecipesAndArtifacts(request, Optional.empty()).getResponse()
                .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);

        // Deployment to add a component
        CreateLocalDeploymentRequest createLocalDeploymentRequest = new CreateLocalDeploymentRequest();
        createLocalDeploymentRequest.setRootComponentVersionsToAdd(Collections.singletonMap(TEST_SERVICE_NAME, "1.0.1"));
        CountDownLatch serviceLatch = waitForServiceToComeInState(TEST_SERVICE_NAME, State.RUNNING, kernel);

        CreateLocalDeploymentResponse addComponentDeploymentResponse =
                clientConnection.createLocalDeployment(createLocalDeploymentRequest, Optional.empty()).getResponse()
                        .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);

        String deploymentId1 = addComponentDeploymentResponse.getDeploymentId();
        CountDownLatch deploymentLatch = waitForDeploymentToBeSuccessful(deploymentId1, kernel);

        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(deploymentLatch.await(LOCAL_DEPLOYMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        GetComponentDetailsRequest getComponentDetailsRequest = new GetComponentDetailsRequest();
        getComponentDetailsRequest.setComponentName(TEST_SERVICE_NAME);
        ComponentDetails componentDetails = clientConnection.getComponentDetails(getComponentDetailsRequest, Optional.empty())
                .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS).getComponentDetails();

        assertEquals("1.0.1", componentDetails.getVersion());


        // Deployment to remove a component
        createLocalDeploymentRequest = new CreateLocalDeploymentRequest();
        createLocalDeploymentRequest.setRootComponentsToRemove(Arrays.asList(TEST_SERVICE_NAME));
        serviceLatch = waitForServiceToComeInState(TEST_SERVICE_NAME, State.FINISHED, kernel);
        CreateLocalDeploymentResponse deploymentToRemoveComponentResponse = clientConnection.createLocalDeployment(createLocalDeploymentRequest, Optional.empty()).getResponse()
                .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);
        String deploymentId2 = deploymentToRemoveComponentResponse.getDeploymentId();
        deploymentLatch = waitForDeploymentToBeSuccessful(deploymentId2, kernel);
        assertTrue(serviceLatch.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(deploymentLatch.await(LOCAL_DEPLOYMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        ignoreExceptionOfType(context, ServiceLoadException.class);


        GetComponentDetailsRequest getRemovedComponent = new GetComponentDetailsRequest();
        getRemovedComponent.setComponentName(TEST_SERVICE_NAME);

        ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                clientConnection.getComponentDetails(getRemovedComponent, Optional.empty())
                        .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS).getComponentDetails());
        assertEquals(ResourceNotFoundError.class, executionException.getCause().getClass());

        ListLocalDeploymentsRequest listLocalDeploymentsRequest = new ListLocalDeploymentsRequest();
        ListLocalDeploymentsResponse listLocalDeploymentsResponse = clientConnection
                .listLocalDeployments(listLocalDeploymentsRequest, Optional.empty()).getResponse()
                .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);

        List<String> localDeploymentIds =
                listLocalDeploymentsResponse.getLocalDeployments().stream().map(ld -> ld.getDeploymentId())
                        .collect(Collectors.toList());
        assertThat(localDeploymentIds, containsInAnyOrder(deploymentId1, deploymentId2));

    }

    @Test
    @Order(8)
    void GIVEN_kernel_running_WHEN_change_configuration_and_deployment_THEN_kernel_copies_artifacts_correctly(ExtensionContext context)
            throws Exception {

        ignoreExceptionOfType(context, PackageDownloadException.class);
        // updated recipes
        Path recipesPath = Paths.get(this.getClass().getResource("recipes").toURI());
        Path artifactsPath = Paths.get(this.getClass().getResource("artifacts").toURI());
        UpdateRecipesAndArtifactsRequest request = new UpdateRecipesAndArtifactsRequest();
        request.setRecipeDirectoryPath(recipesPath.toString());
        request.setArtifactsDirectoryPath(artifactsPath.toString());
        clientConnection.updateRecipesAndArtifacts(request, Optional.empty()).getResponse()
                .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);

        assertTrue(Files.exists(kernel.getNucleusPaths().componentStorePath().resolve(ComponentStore.ARTIFACT_DIRECTORY)
                .resolve("Component1").resolve("1.0.0").resolve("run.sh")));

        Map<String, Map<String, Object>> componentToConfiguration;
        String update = "{\"Component1\":{\"MERGE\":{\"Message\":\"NewWorld\"}}}";
        componentToConfiguration = OBJECT_MAPPER.readValue(update, Map.class);

        CreateLocalDeploymentRequest createLocalDeploymentRequest = new CreateLocalDeploymentRequest();
        createLocalDeploymentRequest.setGroupName("NewGroup");
        createLocalDeploymentRequest.setRootComponentVersionsToAdd(Collections.singletonMap("Component1", "1.0.0"));
        createLocalDeploymentRequest.setComponentToConfiguration(componentToConfiguration);

        CountDownLatch waitForComponent1ToRun = waitForServiceToComeInState("Component1", State.RUNNING, kernel);
        CreateLocalDeploymentResponse addComponentDeploymentResponse =
                clientConnection.createLocalDeployment(createLocalDeploymentRequest, Optional.empty()).getResponse()
                        .get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);

        String deploymentId1 = addComponentDeploymentResponse.getDeploymentId();
        CountDownLatch waitFordeploymentId1 = waitForDeploymentToBeSuccessful(deploymentId1, kernel);
        assertTrue(waitForComponent1ToRun.await(SERVICE_STATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(waitFordeploymentId1.await(LOCAL_DEPLOYMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        GetComponentDetailsRequest getComponentDetailsRequest = new GetComponentDetailsRequest();
        getComponentDetailsRequest.setComponentName("Component1");
        ComponentDetails componentDetails = clientConnection.getComponentDetails(getComponentDetailsRequest, Optional.empty())
                .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS).getComponentDetails();
        assertEquals("NewWorld", componentDetails.getConfiguration().get("Message"));
    }

    @Test
    @Order(9)
    void GIVEN_kernel_running_WHEN_CLI_authorized_groups_updated_THEN_old_token_revoked_and_new_token_accepted(ExtensionContext context)
            throws Exception {

        ignoreExceptionOfType(context, UnauthenticatedException.class);
        String oldAuthToken = getAuthTokenFromInfoFile();
        CountDownLatch awaitIpcServiceLatch = new CountDownLatch(1);
        GlobalStateChangeListener listener = getListenerForServiceRunning(awaitIpcServiceLatch, CLI_SERVICE);
        kernel.getContext().addGlobalStateChangeListener(listener);

        String validGid;
        if (Exec.isWindows) {
            // GG_NEEDS_REVIEW: TODO support windows
            validGid = "0";
        } else {
            validGid = selectAValidGid();
        }
        assertNotNull(validGid, "Failed to find a single valid GID on this test instance");
        kernel.locate(CLI_SERVICE).getConfig().lookup(PARAMETERS_CONFIG_KEY, posixGroups).withValue(validGid);
        assertTrue(awaitIpcServiceLatch.await(10, TimeUnit.SECONDS));
        kernel.getContext().removeGlobalStateChangeListener(listener);
        ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                IPCTestUtils.connectToGGCOverEventStreamIPC(TestUtils.getSocketOptionsForIPC(),
                        oldAuthToken, kernel));
        assertEquals(AccessDeniedException.class, executionException.getCause().getClass());

        try(EventStreamRPCConnection eventStreamRPCConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(TestUtils.getSocketOptionsForIPC(),
                getAuthTokenFromInfoFile(), kernel)){
            assertFalse(eventStreamRPCConnection.getConnection().isClosed());
        }
    }


    private String getAuthTokenFromInfoFile() throws IOException {
        File[] authFiles = kernel.getNucleusPaths().cliIpcInfoPath().toFile().listFiles();
        assertEquals(1, authFiles.length);

        Map<String, String> ipcInfo = OBJECT_MAPPER.readValue(Files.readAllBytes(authFiles[0].toPath()), Map.class);
        return ipcInfo.get(CLI_AUTH_TOKEN);
    }

    private String selectAValidGid() throws IOException, InterruptedException {
        String groups = Exec.cmd("groups");
        for (String group : groups.split(" ")) {
            long gid;
            try {
                gid = ((UnixPlatform)Platform.getInstance()).lookupGroupByName(group).getGID();
                return Long.toString(gid);
            } catch (IOException | NumberFormatException ignore) {
            }
        }
        return null;
    }
}
