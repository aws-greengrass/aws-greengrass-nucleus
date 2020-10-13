package com.aws.greengrass.builtin.services.cli;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import generated.software.amazon.awssdk.iot.greengrass.model.ComponentDetails;
import generated.software.amazon.awssdk.iot.greengrass.model.CreateLocalDeploymentRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.DeploymentStatus;
import generated.software.amazon.awssdk.iot.greengrass.model.GetComponentDetailsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetComponentDetailsResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.GetLocalDeploymentStatusRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetLocalDeploymentStatusResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.InvalidArgumentsError;
import generated.software.amazon.awssdk.iot.greengrass.model.InvalidArtifactsDirectoryPathError;
import generated.software.amazon.awssdk.iot.greengrass.model.LifecycleState;
import generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentsResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.ResourceNotFoundError;
import generated.software.amazon.awssdk.iot.greengrass.model.RestartComponentRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ServiceError;
import generated.software.amazon.awssdk.iot.greengrass.model.StopComponentRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateRecipesAndArtifactsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.utils.ImmutableMap;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.aws.greengrass.builtin.services.cli.CLIServiceAgent.PERSISTENT_LOCAL_DEPLOYMENTS;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.ipc.common.IPCErrorStrings.DEPLOYMENTS_QUEUE_NOT_INITIALIZED;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class CLIEventStreamAgentTest {

    private CLIEventStreamAgent cliEventStreamAgent;

    private static final String TEST_SERVICE = "TestService";
    private static final String MOCK_GROUP = "mockGroup";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Mock
    Kernel kernel;

    @Mock
    OperationContinuationHandlerContext mockContext;

    @TempDir
    Path mockPath;

    @Mock
    DeploymentQueue deploymentQueue;

    @BeforeEach
    public void setup() {
        when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        cliEventStreamAgent = new CLIEventStreamAgent();
        cliEventStreamAgent.setKernel(kernel);
    }

    @Test
    public void test_GetComponentDetails_empty_component_name() {
        GetComponentDetailsRequest request = new GetComponentDetailsRequest();
        assertThrows(InvalidArgumentsError.class, () ->
                cliEventStreamAgent.getGetComponentDetailsHandler(mockContext).handleRequest(request));
    }

    @Test
    public void test_GetComponentDetails_component_does_not_exist(ExtensionContext context) throws ServiceLoadException {
        ignoreExceptionOfType(context, ServiceLoadException.class);
        GetComponentDetailsRequest request = new GetComponentDetailsRequest();
        request.setComponentName(TEST_SERVICE);
        when(kernel.locate(TEST_SERVICE)).thenThrow(new ServiceLoadException("error"));
        assertThrows(ResourceNotFoundError.class, () ->
                cliEventStreamAgent.getGetComponentDetailsHandler(mockContext).handleRequest(request));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void test_GetComponentDetails_successful() throws ServiceLoadException, IOException {
        GetComponentDetailsRequest request = new GetComponentDetailsRequest();
        request.setComponentName(TEST_SERVICE);
        GreengrassService mockTestService = mock(GreengrassService.class);
        when(mockTestService.getName()).thenReturn(TEST_SERVICE);
        when(mockTestService.getState()).thenReturn(State.RUNNING);
        Context context = new Context();
        Topics mockServiceConfig = Topics.of(context, TEST_SERVICE, null);
        mockServiceConfig.lookup(VERSION_CONFIG_KEY).withValue("1.0.0");
        Map<String, Object> mockParameterConfig = ImmutableMap.of("param1", "value1");
        mockServiceConfig.lookupTopics(PARAMETERS_CONFIG_KEY).replaceAndWait(mockParameterConfig);
        when(mockTestService.getServiceConfig()).thenReturn(mockServiceConfig);
        when(kernel.locate(TEST_SERVICE)).thenReturn(mockTestService);
        GetComponentDetailsResponse response =
                cliEventStreamAgent.getGetComponentDetailsHandler(mockContext).handleRequest(request);
        assertEquals(TEST_SERVICE, response.getComponentDetails().getComponentName());
        assertEquals(LifecycleState.RUNNING, response.getComponentDetails().getState());
        assertEquals("1.0.0", response.getComponentDetails().getVersion());
        assertEquals(mockParameterConfig, response.getComponentDetails().getConfiguration());
        context.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void test_GetListComponent_success() {
        ListComponentsRequest request = new ListComponentsRequest();
        GreengrassService mockTestService = mock(GreengrassService.class);
        GreengrassService mockMainService = mock(GreengrassService.class);
        when(mockTestService.getName()).thenReturn(TEST_SERVICE);
        when(mockTestService.getState()).thenReturn(State.RUNNING);
        Context context = new Context();
        Topics mockServiceConfig = Topics.of(context, TEST_SERVICE, null);
        mockServiceConfig.lookup(VERSION_CONFIG_KEY).withValue("1.0.0");
        Map<String, Object> mockParameterConfig = ImmutableMap.of("param1", "value1");
        mockServiceConfig.lookupTopics(PARAMETERS_CONFIG_KEY).replaceAndWait(mockParameterConfig);
        when(mockTestService.getServiceConfig()).thenReturn(mockServiceConfig);
        when(mockMainService.getName()).thenReturn("main");
        when(kernel.getMain()).thenReturn(mockMainService);
        when(kernel.orderedDependencies()).thenReturn(Arrays.asList(mockTestService, mockMainService));
        ListComponentsResponse response =
                cliEventStreamAgent.getListComponentsHandler(mockContext).handleRequest(request);
        assertEquals(1, response.getComponents().size());
        ComponentDetails componentDetails = response.getComponents().get(0);
        assertEquals(TEST_SERVICE, componentDetails.getComponentName());
        assertEquals(mockParameterConfig, componentDetails.getConfiguration());
        assertEquals("1.0.0", componentDetails.getVersion());
    }

    @Test
    public void testRestartComponent_emptyComponentName() {
        RestartComponentRequest restartComponentRequest = new RestartComponentRequest();
        assertThrows(InvalidArgumentsError.class,
                () -> cliEventStreamAgent.getRestartComponentsHandler(mockContext).handleRequest(restartComponentRequest));
    }

    @Test
    public void testRestartComponent_component_not_found(ExtensionContext context) throws ServiceLoadException {
        ignoreExceptionOfType(context, ServiceLoadException.class);
        RestartComponentRequest restartComponentRequest = new RestartComponentRequest();
        restartComponentRequest.setComponentName("INVALID_COMPONENT");
        when(kernel.locate("INVALID_COMPONENT")).thenThrow(new ServiceLoadException("error"));
        assertThrows(ResourceNotFoundError.class,
                () -> cliEventStreamAgent.getRestartComponentsHandler(mockContext).handleRequest(restartComponentRequest));
    }

    @Test
    public void testRestartComponent_component_restart() throws ServiceLoadException {
        RestartComponentRequest restartComponentRequest = new RestartComponentRequest();
        restartComponentRequest.setComponentName(TEST_SERVICE);
        GreengrassService mockTestService = mock(GreengrassService.class);
        when(kernel.locate(TEST_SERVICE)).thenReturn(mockTestService);
        cliEventStreamAgent.getRestartComponentsHandler(mockContext).handleRequest(restartComponentRequest);
        verify(mockTestService).requestRestart();
    }

    @Test
    public void testStopComponent_emptyComponentName() {
        StopComponentRequest stopComponentRequest = new StopComponentRequest();
        assertThrows(InvalidArgumentsError.class,
                () -> cliEventStreamAgent.getStopComponentsHandler(mockContext).handleRequest(stopComponentRequest));
    }

    @Test
    public void testStopComponent_component_not_found(ExtensionContext context) throws ServiceLoadException {
        ignoreExceptionOfType(context, ServiceLoadException.class);
        StopComponentRequest stopComponentRequest = new StopComponentRequest();
        stopComponentRequest.setComponentName("INVALID_COMPONENT");
        when(kernel.locate("INVALID_COMPONENT")).thenThrow(new ServiceLoadException("error"));
        assertThrows(ResourceNotFoundError.class,
                () -> cliEventStreamAgent.getStopComponentsHandler(mockContext).handleRequest(stopComponentRequest));
    }

    @Test
    public void testStopComponent_component_restart() throws ServiceLoadException {
        StopComponentRequest stopComponentRequest = new StopComponentRequest();
        stopComponentRequest.setComponentName(TEST_SERVICE);
        GreengrassService mockTestService = mock(GreengrassService.class);
        when(kernel.locate(TEST_SERVICE)).thenReturn(mockTestService);
        cliEventStreamAgent.getStopComponentsHandler(mockContext).handleRequest(stopComponentRequest);
        verify(mockTestService).requestStop();
    }

    @Test
    public void testUpdateRecipesAndArtifacts_empty_paths() {
        UpdateRecipesAndArtifactsRequest request = new UpdateRecipesAndArtifactsRequest();
        assertThrows(InvalidArgumentsError.class,
                () -> cliEventStreamAgent.getUpdateRecipesAndArtifactsHandler(mockContext).handleRequest(request));
    }

    @Test
    public void testUpdateRecipesAndArtifacts_invalid_paths(ExtensionContext context) {
        ignoreExceptionOfType(context, NoSuchFileException.class);
        UpdateRecipesAndArtifactsRequest request = new UpdateRecipesAndArtifactsRequest();
        request.setArtifactsDirectoryPath("/InvalidPath");
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        when(kernel.getNucleusPaths()).thenReturn(nucleusPaths);
        when(nucleusPaths.componentStorePath()).thenReturn(mockPath);
        assertThrows(InvalidArtifactsDirectoryPathError.class,
                () -> cliEventStreamAgent.getUpdateRecipesAndArtifactsHandler(mockContext).handleRequest(request));
    }

    @Test
    public void testUpdateRecipesAndArtifacts_successful_update(ExtensionContext context) throws IOException {
        ignoreExceptionOfType(context, NoSuchFileException.class);
        UpdateRecipesAndArtifactsRequest request = new UpdateRecipesAndArtifactsRequest();
        Path mockArtifactsDirectoryPath = Files.createTempDirectory("mockArtifactsDirectoryPath");
        request.setArtifactsDirectoryPath(mockArtifactsDirectoryPath.toString());
        Path mockRecipesDirectoryPath = Files.createTempDirectory("mockRecipesDirectoryPath");
        request.setRecipeDirectoryPath(mockRecipesDirectoryPath.toString());
        Path componentPath = Files.createDirectories(mockRecipesDirectoryPath.resolve("SampleComponent-1.0.0"));
        Files.createFile(componentPath.resolve("sampleRecipe.xml"));
        Files.createFile(mockArtifactsDirectoryPath.resolve("artifact.zip"));
        Files.createDirectories(mockPath.resolve(ComponentStore.RECIPE_DIRECTORY));
        Files.createDirectories(mockPath.resolve(ComponentStore.ARTIFACT_DIRECTORY));
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        when(kernel.getNucleusPaths()).thenReturn(nucleusPaths);
        when(nucleusPaths.componentStorePath()).thenReturn(mockPath);
        cliEventStreamAgent.getUpdateRecipesAndArtifactsHandler(mockContext).handleRequest(request);
        assertTrue(Files.exists(mockPath.resolve(ComponentStore.RECIPE_DIRECTORY)
                .resolve("SampleComponent-1.0.0").resolve("sampleRecipe.xml")));
        assertTrue(Files.exists(mockPath.resolve(ComponentStore.ARTIFACT_DIRECTORY).resolve("artifact.zip")));
    }

    @Test
    public void testCreateLocalDeployment_deployments_Q_not_initialized() {
        Topics mockCliConfig = mock(Topics.class);
        CreateLocalDeploymentRequest request = new CreateLocalDeploymentRequest();
       try {
            cliEventStreamAgent.getCreateLocalDeploymentHandler(mockContext, mockCliConfig).handleRequest(request);
        } catch (ServiceError e) {
           assertEquals(DEPLOYMENTS_QUEUE_NOT_INITIALIZED, e.getMessage());
           return;
       }
       fail();
    }

    @Test
    public void testCreateLocalDeployment_successfull() throws JsonProcessingException {
        Topics mockCliConfig = mock(Topics.class);
        Topics localDeployments = mock(Topics.class);
        Topics localDeploymentDetailsTopics = mock(Topics.class);
        when(localDeployments.lookupTopics(any())).thenReturn(localDeploymentDetailsTopics);
        when(mockCliConfig.lookupTopics(PERSISTENT_LOCAL_DEPLOYMENTS)).thenReturn(localDeployments);
        when(deploymentQueue.offer(any())).thenReturn(true);
        cliEventStreamAgent.setDeploymentQueue(deploymentQueue);
        CreateLocalDeploymentRequest request = new CreateLocalDeploymentRequest();
        request.setGroupName(MOCK_GROUP);
        request.setRootComponentVersionsToAdd(ImmutableMap.of(TEST_SERVICE, "1.0.0"));
        request.setRootComponentsToRemove(Arrays.asList("SomeService"));
        Map<String, Map<String, Object>> componentToConfig = new HashMap<>();
        componentToConfig.put(TEST_SERVICE, ImmutableMap.of("param1", "value1"));
        request.setComponentToConfiguration(componentToConfig);
        cliEventStreamAgent.getCreateLocalDeploymentHandler(mockContext, mockCliConfig).handleRequest(request);
        ArgumentCaptor<Deployment> deploymentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(deploymentQueue).offer(deploymentCaptor.capture());
        String deploymentDoc = deploymentCaptor.getValue().getDeploymentDocument();
        LocalOverrideRequest localOverrideRequest = OBJECT_MAPPER.readValue(deploymentDoc, LocalOverrideRequest.class);
        assertEquals(MOCK_GROUP, localOverrideRequest.getGroupName());
        assertTrue(localOverrideRequest.getComponentsToMerge().containsKey(TEST_SERVICE));
        assertTrue(localOverrideRequest.getComponentsToMerge().containsValue("1.0.0"));
        assertTrue(localOverrideRequest.getComponentsToRemove().contains("SomeService"));
        assertNotNull(localOverrideRequest.getComponentNameToConfig().get(TEST_SERVICE));
        assertEquals("value1", localOverrideRequest.getComponentNameToConfig()
                .get(TEST_SERVICE).get("param1"));


        verify(localDeployments).lookupTopics(localOverrideRequest.getRequestId());
        ArgumentCaptor<Map> deploymentDetailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(localDeploymentDetailsTopics).replaceAndWait(deploymentDetailsCaptor.capture());
        CLIEventStreamAgent.LocalDeploymentDetails localDeploymentDetails =
                OBJECT_MAPPER.convertValue((Map<String, Object>)deploymentDetailsCaptor.getValue(),
                CLIEventStreamAgent.LocalDeploymentDetails.class);
        assertEquals(Deployment.DeploymentType.LOCAL, localDeploymentDetails.getDeploymentType());
        assertEquals(DeploymentStatus.QUEUED, localDeploymentDetails.getStatus());
    }

    @Test
    public void testGetLocalDeploymentStatus_invalidDeploymentId() {
        Topics mockCliConfig = mock(Topics.class);
        GetLocalDeploymentStatusRequest request = new GetLocalDeploymentStatusRequest();
        request.setDeploymentId("InvalidId");
        assertThrows(InvalidArgumentsError.class,
                () -> cliEventStreamAgent.getGetLocalDeploymentStatusHandler(mockContext,
            mockCliConfig).handleRequest(request));
    }

    @Test
    public void testGetLocalDeploymentStatus_deploymentId_not_exist() {
        Topics localDeployments = mock(Topics.class);
        Topics mockCliConfig = mock(Topics.class);
        String deploymentId = UUID.randomUUID().toString();
        when(mockCliConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS)).thenReturn(localDeployments);
        when(localDeployments.findTopics(deploymentId)).thenReturn(null);
        GetLocalDeploymentStatusRequest request = new GetLocalDeploymentStatusRequest();
        request.setDeploymentId(deploymentId);
        assertThrows(ResourceNotFoundError.class,
                () -> cliEventStreamAgent.getGetLocalDeploymentStatusHandler(mockContext,
                        mockCliConfig).handleRequest(request));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void testGetLocalDeploymentStatus_successful() throws IOException {
        Topics localDeployments = mock(Topics.class);
        Topics mockCliConfig = mock(Topics.class);
        Context context = new Context();
        String deploymentId = UUID.randomUUID().toString();
        Topics mockLocalDeployment = Topics.of(context, deploymentId, null);
        mockLocalDeployment.lookup(DEPLOYMENT_STATUS_KEY_NAME).withValue(DeploymentStatus.IN_PROGRESS.toString());
        when(mockCliConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS)).thenReturn(localDeployments);
        when(localDeployments.findTopics(deploymentId)).thenReturn(mockLocalDeployment);
        GetLocalDeploymentStatusRequest request = new GetLocalDeploymentStatusRequest();
        request.setDeploymentId(deploymentId);
        GetLocalDeploymentStatusResponse response = cliEventStreamAgent.getGetLocalDeploymentStatusHandler(mockContext,
                        mockCliConfig).handleRequest(request);
        assertEquals(deploymentId, response.getDeployment().getDeploymentId());
        assertEquals(DeploymentStatus.IN_PROGRESS, response.getDeployment().getStatus());
        context.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void testListLocalDeployment_no_local_deployments() throws IOException {
        Topics mockCliConfig = mock(Topics.class);
        Context context = new Context();
        Topics localDeployments = Topics.of(context, "localDeployments", null);
        when(mockCliConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS)).thenReturn(localDeployments);
        ListLocalDeploymentsRequest request = new ListLocalDeploymentsRequest();
        ListLocalDeploymentsResponse response = cliEventStreamAgent.getListLocalDeploymentsHandler(mockContext,
                mockCliConfig).handleRequest(request);
        assertEquals(0, response.getLocalDeployments().size());
        context.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void testListLocalDeployment_successful() throws IOException {
        Topics mockCliConfig = mock(Topics.class);
        Context context = new Context();
        Topics localDeployments = Topics.of(context, "localDeployments", null);
        String deploymentId1 = UUID.randomUUID().toString();
        localDeployments.lookupTopics(deploymentId1).lookup(DEPLOYMENT_STATUS_KEY_NAME)
                .withValue(DeploymentStatus.IN_PROGRESS.toString());
        String deploymentId2 = UUID.randomUUID().toString();
        localDeployments.lookupTopics(deploymentId2).lookup(DEPLOYMENT_STATUS_KEY_NAME)
                .withValue(DeploymentStatus.SUCCEEDED.toString());
        when(mockCliConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS)).thenReturn(localDeployments);
        ListLocalDeploymentsRequest request = new ListLocalDeploymentsRequest();
        ListLocalDeploymentsResponse response = cliEventStreamAgent.getListLocalDeploymentsHandler(mockContext,
                mockCliConfig).handleRequest(request);
        assertEquals(2, response.getLocalDeployments().size());
        response.getLocalDeployments().stream().forEach(ld -> {
            if (ld.getDeploymentId().equals(deploymentId1)) {
                assertEquals(DeploymentStatus.IN_PROGRESS, ld.getStatus());
            } else if (ld.getDeploymentId().equals(deploymentId2)) {
                assertEquals(DeploymentStatus.SUCCEEDED, ld.getStatus());
            } else {
                fail("Invalid deploymentId found in list of local deployments");
            }
        });
        context.close();
    }

}
