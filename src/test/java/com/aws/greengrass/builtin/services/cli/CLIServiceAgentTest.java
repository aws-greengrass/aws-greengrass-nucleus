/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.cli;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.amazon.aws.iot.greengrass.component.common.SerializerFactory;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.ipc.services.cli.exceptions.ComponentNotFoundError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidArgumentsError;
import com.aws.greengrass.ipc.services.cli.exceptions.ResourceNotFoundError;
import com.aws.greengrass.ipc.services.cli.models.ComponentDetails;
import com.aws.greengrass.ipc.services.cli.models.CreateLocalDeploymentRequest;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsRequest;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsResponse;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusRequest;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusResponse;
import com.aws.greengrass.ipc.services.cli.models.LifecycleState;
import com.aws.greengrass.ipc.services.cli.models.ListComponentsResponse;
import com.aws.greengrass.ipc.services.cli.models.ListLocalDeploymentResponse;
import com.aws.greengrass.ipc.services.cli.models.LocalDeployment;
import com.aws.greengrass.ipc.services.cli.models.RestartComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.StopComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.UpdateRecipesAndArtifactsRequest;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.aws.greengrass.builtin.services.cli.CLIServiceAgent.LOCAL_DEPLOYMENT_RESOURCE;
import static com.aws.greengrass.builtin.services.cli.CLIServiceAgent.PERSISTENT_LOCAL_DEPLOYMENTS;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.DEFAULT_GROUP_NAME;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class CLIServiceAgentTest {

    private static final String MOCK_COMPONENT_NAME = "MockComponent";
    private static final String MOCK_VERSION = "1.0.0";
    private static final String MOCK_GROUP_NAME = "MockGroup";
    private static final String MOCK_DEPLOYMENT_ID = UUID.randomUUID().toString();
    private static final String MOCK_DEPLOYMENT_ID2 = UUID.randomUUID().toString();
    private static final String MOCK_PARAM_KEY = "ParamKey";
    private static final String MOCK_PARAM_VALUE = "ParamValue";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Mock
    private Kernel kernel;
    @Mock
    private NucleusPaths nucleusPaths;
    @Mock
    private DeploymentQueue deploymentQueue;
    private CLIServiceAgent cliServiceAgent;
    private final Context context = new Context();

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        lenient().when(kernel.getNucleusPaths()).thenReturn(nucleusPaths);
        cliServiceAgent = new CLIServiceAgent(kernel, deploymentQueue);
    }

    @AfterEach
    void shutdown() throws IOException {
        context.close();
    }

    @Test
    void testGetComponentDetails_success() throws Exception {
        GetComponentDetailsRequest request = mock(GetComponentDetailsRequest.class);
        when(request.getComponentName()).thenReturn(MOCK_COMPONENT_NAME);
        GreengrassService mockService = createMockService(MOCK_COMPONENT_NAME,
                State.RUNNING, MOCK_VERSION, Collections.singletonMap(MOCK_PARAM_KEY, MOCK_PARAM_VALUE));
        when(kernel.locate(eq(MOCK_COMPONENT_NAME))).thenReturn(mockService);
        GetComponentDetailsResponse response = cliServiceAgent.getComponentDetails(request);
        assertEquals(LifecycleState.RUNNING, response.getComponentDetails().getState());
        assertEquals(MOCK_COMPONENT_NAME, response.getComponentDetails().getComponentName());
        assertEquals(MOCK_VERSION, response.getComponentDetails().getVersion());
        assertEquals(Collections.singletonMap(MOCK_PARAM_KEY, MOCK_PARAM_VALUE),
                response.getComponentDetails().getConfiguration());
    }

    @Test
    void testGetComponentDetails_component_does_not_exist(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        GetComponentDetailsRequest request = mock(GetComponentDetailsRequest.class);
        when(request.getComponentName()).thenReturn(MOCK_COMPONENT_NAME);
        when(kernel.locate(eq(MOCK_COMPONENT_NAME))).thenThrow(new ServiceLoadException("Error"));
        assertThrows(ComponentNotFoundError.class, ()->cliServiceAgent.getComponentDetails(request));
    }

    @Test
    void testGetComponentDetails_received_empty_component_name() throws Exception {
        GetComponentDetailsRequest request = GetComponentDetailsRequest.builder().build();
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.getComponentDetails(request));
        GetComponentDetailsRequest request2 = GetComponentDetailsRequest.builder().componentName("").build();
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.getComponentDetails(request2));
    }

    @Test
    void testListComponents_success() {
        List<GreengrassService> servicesInKernel = Arrays.asList(
                createMockService("COMPONENT1", State.RUNNING, "1.0.0",
                        Collections.singletonMap(MOCK_PARAM_KEY, MOCK_PARAM_VALUE)),
                createMockService("COMPONENT2", State.FINISHED, "0.9.1",
                        Collections.singletonMap(MOCK_PARAM_KEY, MOCK_PARAM_VALUE)));
        when(kernel.orderedDependencies()).thenReturn(servicesInKernel);
        GreengrassService mockMainService = mock(GreengrassService.class);
        when(mockMainService.getName()).thenReturn("main");
        when(kernel.getMain()).thenReturn(mockMainService);
        ListComponentsResponse response = cliServiceAgent.listComponents();
        ComponentDetails componentDetails1 = ComponentDetails.builder()
                                                .componentName("COMPONENT1")
                                                .state(LifecycleState.RUNNING)
                                                .version("1.0.0")
                                                .configuration(Collections.singletonMap(MOCK_PARAM_KEY, MOCK_PARAM_VALUE))
                                                .build();
        ComponentDetails componentDetails2 = ComponentDetails.builder()
                .componentName("COMPONENT2")
                .state(LifecycleState.FINISHED)
                .version("0.9.1")
                .configuration(Collections.singletonMap(MOCK_PARAM_KEY, MOCK_PARAM_VALUE))
                .build();
        assertTrue(response.getComponents().contains(componentDetails1));
        assertTrue(response.getComponents().contains(componentDetails2));
    }

    @Test
    void testRestartComponent_success() throws Exception {
        RestartComponentRequest request = RestartComponentRequest.builder().componentName(MOCK_COMPONENT_NAME).build();
        GreengrassService mockService = mock(GreengrassService.class);
        when(kernel.locate(eq(MOCK_COMPONENT_NAME))).thenReturn(mockService);
        cliServiceAgent.restartComponent(request);
        verify(mockService).requestRestart();
    }

    @Test
    void testRestartComponent_component_does_not_exist(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        RestartComponentRequest request = RestartComponentRequest.builder().componentName(MOCK_COMPONENT_NAME).build();
        when(kernel.locate(eq(MOCK_COMPONENT_NAME))).thenThrow(new ServiceLoadException("Error"));
        assertThrows(ComponentNotFoundError.class, ()->cliServiceAgent.restartComponent(request));
    }

    @Test
    void testRestartComponent_empty_component_name_in_request() throws Exception {
        RestartComponentRequest request = RestartComponentRequest.builder().build();
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.restartComponent(request));
        RestartComponentRequest request2 = RestartComponentRequest.builder().componentName("").build();
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.restartComponent(request2));
    }

    @Test
    void testStopComponent_success() throws Exception {
        StopComponentRequest request = StopComponentRequest.builder().componentName(MOCK_COMPONENT_NAME).build();
        GreengrassService mockService = mock(GreengrassService.class);
        when(kernel.locate(eq(MOCK_COMPONENT_NAME))).thenReturn(mockService);
        cliServiceAgent.stopComponent(request);
        verify(mockService).requestStop();
    }

    @Test
    void testStopComponent_component_does_not_exist(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceLoadException.class);
        StopComponentRequest request = StopComponentRequest.builder().componentName(MOCK_COMPONENT_NAME).build();
        when(kernel.locate(eq(MOCK_COMPONENT_NAME))).thenThrow(new ServiceLoadException("Error"));
        assertThrows(ComponentNotFoundError.class, ()->cliServiceAgent.stopComponent(request));
    }

    @Test
    void testStopComponent_empty_component_name_in_request() throws Exception {
        StopComponentRequest request = StopComponentRequest.builder().build();
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.stopComponent(request));
        StopComponentRequest request2 = StopComponentRequest.builder().componentName("").build();
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.stopComponent(request2));
    }

    @Test
    void testUpdateRecipesAndArtifacts_success() throws Exception {
        Path tempDirectory = Files.createTempDirectory("cliTest");
        Path kernelLocalStore = tempDirectory.resolve("kernelLocalStore");
        Path kernelArtifactsPath = kernelLocalStore.resolve(ComponentStore.ARTIFACT_DIRECTORY);
        File kernelArtifactsDirectory = new File(kernelArtifactsPath.toString());
        kernelArtifactsDirectory.mkdirs();
        Path kernelRecipesPath = kernelLocalStore.resolve(ComponentStore.RECIPE_DIRECTORY);
        File kernelRecipeDirectory = new File(kernelRecipesPath.toString());
        kernelRecipeDirectory.mkdirs();
        Path artifactsDirectoryPath = tempDirectory.resolve("artifactsDirectory");
        Path recipeDirectoryPath = tempDirectory.resolve("recipeDirectoryPath");

        File componentRecipeDirectory = recipeDirectoryPath.toFile();
        componentRecipeDirectory.mkdirs();
        Path recipeFile = componentRecipeDirectory.toPath().resolve("recipe.yaml");
        Files.write(recipeFile, SerializerFactory.getRecipeSerializer().writeValueAsBytes(ComponentRecipe.builder()
                        .componentName("MyComponent").recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                        .componentVersion(new Semver("1.0.0")).build()));
        File componentArtifactDirectory = new File(artifactsDirectoryPath.resolve("MyComponent-1.0.0").toString());
        componentArtifactDirectory.mkdirs();
        File artifactFile = new File(componentArtifactDirectory.getAbsolutePath(), "binary.exe");
        artifactFile.createNewFile();
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .artifactDirectoryPath(artifactsDirectoryPath.toString())
                .recipeDirectoryPath(recipeDirectoryPath.toString())
                .build();
        when(nucleusPaths.componentStorePath()).thenReturn(kernelLocalStore);
        cliServiceAgent.updateRecipesAndArtifacts(request);
        assertTrue(Files.exists(kernelRecipesPath.resolve("MyComponent-1.0.0.yaml")));
        assertTrue(Files.exists(kernelArtifactsPath.resolve("MyComponent-1.0.0").resolve("binary.exe")));
    }

    @Test
    void testUpdateRecipesAndArtifacts_no_directory_path_provided() {
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .artifactDirectoryPath("")
                .recipeDirectoryPath("")
                .build();
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.updateRecipesAndArtifacts(request));
        UpdateRecipesAndArtifactsRequest request2 = UpdateRecipesAndArtifactsRequest.builder()
                .build();
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.updateRecipesAndArtifacts(request2));
    }

    @Test
    void testUpdateRecipesAndArtifacts_only_recipe_path_given() throws Exception {
        Path tempDirectory = Files.createTempDirectory("cliTest");
        Path kernelLocalStore = tempDirectory.resolve("kernelLocalStore");
        Path kernelRecipesPath = kernelLocalStore.resolve(ComponentStore.RECIPE_DIRECTORY);
        File kernelRecipeDirectory = new File(kernelRecipesPath.toString());
        kernelRecipeDirectory.mkdirs();
        Path recipeDirectoryPath = tempDirectory.resolve("recipeDirectoryPath");

        File componentRecipeDirectory = recipeDirectoryPath.toFile();
        componentRecipeDirectory.mkdirs();
        Path recipeFile = componentRecipeDirectory.toPath().resolve("MyComponent.yaml");
        Files.write(recipeFile, SerializerFactory.getRecipeSerializer().writeValueAsBytes(ComponentRecipe.builder()
                        .componentName("MyComponent").recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                        .componentVersion(new Semver("1.0.0")).build()),
                StandardOpenOption.CREATE);
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .recipeDirectoryPath(recipeDirectoryPath.toString())
                .build();
        when(nucleusPaths.componentStorePath()).thenReturn(kernelLocalStore);
        cliServiceAgent.updateRecipesAndArtifacts(request);
        assertTrue(Files.exists(kernelRecipesPath.resolve("MyComponent-1.0.0.yaml")));
    }

    @Test
    void testUpdateRecipesAndArtifacts_only_artifact_provided() throws Exception {
        Path tempDirectory = Files.createTempDirectory("cliTest");
        Path kernelLocalStore = tempDirectory.resolve("kernelLocalStore");
        Path kernelArtifactsPath = kernelLocalStore.resolve(ComponentStore.ARTIFACT_DIRECTORY);
        File kernelArtifactsDirectory = new File(kernelArtifactsPath.toString());
        kernelArtifactsDirectory.mkdirs();

        Path artifactsDirectoryPath = tempDirectory.resolve("artifactsDirectory");

        File componentArtifactDirectory = new File(artifactsDirectoryPath.resolve("MyComponent-1.0.0").toString());
        componentArtifactDirectory.mkdirs();
        File artifactFile = new File(componentArtifactDirectory.getAbsolutePath(), "binary.exe");
        artifactFile.createNewFile();
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder()
                .artifactDirectoryPath(artifactsDirectoryPath.toString())
                .build();
        when(nucleusPaths.componentStorePath()).thenReturn(kernelLocalStore);
        cliServiceAgent.updateRecipesAndArtifacts(request);
        assertTrue(Files.exists(kernelArtifactsPath.resolve("MyComponent-1.0.0").resolve("binary.exe")));
    }

    @Test
    void testCreateLocalDeployment_success() throws Exception {
        Map<String, String> componentToVersion = new HashMap<>();
        componentToVersion.put("Component1", "1.0.0");
        componentToVersion.put("Component2", "2.0.0");
        List<String> componentsToRemove = Arrays.asList("Component3");
        Map<String, Map<String, Object>> componentToConfiguration = new HashMap<>();
        Map<String, Object> component1Configuration = new HashMap<>();
        component1Configuration.put("portNumber", 1000);
        componentToConfiguration.put("Component1", component1Configuration);
        String configUpdateString = "{\"Component1\":{ \"MERGE\": {\"foo\": \"bar\"}}}";
        Map<String, Map<String, Object>> configUpdateMap = mapper.readValue(configUpdateString, Map.class);
        CreateLocalDeploymentRequest request = CreateLocalDeploymentRequest.builder()
                                                .groupName(MOCK_GROUP_NAME)
                                                .rootComponentVersionsToAdd(componentToVersion)
                                                .rootComponentsToRemove(componentsToRemove)
                                                .componentToConfiguration(componentToConfiguration)
                                                .configurationUpdate(configUpdateMap)
                                                .build();

        when(deploymentQueue.offer(any())).thenReturn(true);
        Topics mockServiceConfig = mock(Topics.class);
        Topics mockLocalDeployments = mock(Topics.class);
        Topics mockDeploymentTopics = mock(Topics.class);
        when(mockServiceConfig.lookupTopics(eq(PERSISTENT_LOCAL_DEPLOYMENTS))).thenReturn(mockLocalDeployments);
        when(mockLocalDeployments.lookupTopics(any())).thenReturn(mockDeploymentTopics);
        cliServiceAgent.createLocalDeployment(mockServiceConfig, request);
        ArgumentCaptor<Deployment> argumentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(deploymentQueue).offer(argumentCaptor.capture());
        Deployment deployment = argumentCaptor.getValue();
        assertEquals(Deployment.DeploymentType.LOCAL, deployment.getDeploymentType());
        String deploymentDocumentString = (String) deployment.getDeploymentDocument();
        LocalOverrideRequest localOverrideRequest = OBJECT_MAPPER.readValue(deploymentDocumentString,
                LocalOverrideRequest.class);
        assertEquals(MOCK_GROUP_NAME, localOverrideRequest.getGroupName());
        assertEquals(componentToVersion, localOverrideRequest.getComponentsToMerge());
        assertEquals(componentsToRemove, localOverrideRequest.getComponentsToRemove());
        assertEquals(componentToConfiguration, localOverrideRequest.getComponentNameToConfig());

        Map<String, ConfigurationUpdateOperation> configUpdate = new HashMap<>();
        ConfigurationUpdateOperation configUpdateComponent1 = new ConfigurationUpdateOperation();
        configUpdateComponent1.setValueToMerge(new HashMap<String, Object>(){{
            put("foo", "bar");
        }});
        configUpdate.put("Component1", configUpdateComponent1);
        assertEquals(configUpdate, localOverrideRequest.getConfigurationUpdate());
    }

    @Test
    void testCreateLocalDeployment_no_arguments_accepted() throws Exception {

        CreateLocalDeploymentRequest request = CreateLocalDeploymentRequest.builder()
                .build();
        when(deploymentQueue.offer(any())).thenReturn(true);
        Topics mockServiceConfig = mock(Topics.class);
        Topics mockLocalDeployments = mock(Topics.class);
        Topics mockDeploymentTopics = mock(Topics.class);
        when(mockServiceConfig.lookupTopics(eq(PERSISTENT_LOCAL_DEPLOYMENTS))).thenReturn(mockLocalDeployments);
        when(mockLocalDeployments.lookupTopics(any())).thenReturn(mockDeploymentTopics);
        cliServiceAgent.createLocalDeployment(mockServiceConfig, request);
        ArgumentCaptor<Deployment> argumentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(deploymentQueue).offer(argumentCaptor.capture());
        Deployment deployment = argumentCaptor.getValue();
        assertEquals(Deployment.DeploymentType.LOCAL, deployment.getDeploymentType());
        String deploymentDocumentString = (String) deployment.getDeploymentDocument();
        LocalOverrideRequest localOverrideRequest = OBJECT_MAPPER.readValue(deploymentDocumentString,
                LocalOverrideRequest.class);
        assertEquals(DEFAULT_GROUP_NAME, localOverrideRequest.getGroupName());
    }

    @Test
    void testGetLocalDeploymentStatus_success() throws Exception {
        GetLocalDeploymentStatusRequest request =
                GetLocalDeploymentStatusRequest.builder().deploymentId(MOCK_DEPLOYMENT_ID).build();
        Topics mockServiceConfig = mock(Topics.class);
        Topics mockLocalDeployments = mock(Topics.class);
        Topics mockDeploymentTopics = mock(Topics.class);
        when(mockServiceConfig.findTopics(eq(PERSISTENT_LOCAL_DEPLOYMENTS))).thenReturn(mockLocalDeployments);
        when(mockLocalDeployments.findTopics(eq(MOCK_DEPLOYMENT_ID))).thenReturn(mockDeploymentTopics);
        Topic mockStatusTopic = mock(Topic.class);
        when(mockStatusTopic.getOnce()).thenReturn(DeploymentStatus.IN_PROGRESS.toString());
        when(mockDeploymentTopics.find(eq(DEPLOYMENT_STATUS_KEY_NAME)))
                .thenReturn(mockStatusTopic);
        GetLocalDeploymentStatusResponse response = cliServiceAgent.getLocalDeploymentStatus(mockServiceConfig,
                request);
        assertEquals(DeploymentStatus.IN_PROGRESS, response.getDeployment().getStatus());
    }

    @Test
    void testGetLocalDeploymentStatus_invalid_deploymentId_format() throws Exception {
        GetLocalDeploymentStatusRequest request =
                GetLocalDeploymentStatusRequest.builder().deploymentId("NonUUIDId").build();
        Topics mockServiceConfig = mock(Topics.class);
        assertThrows(InvalidArgumentsError.class, ()->cliServiceAgent.getLocalDeploymentStatus(mockServiceConfig,
                request));
    }

    @Test
    void testGetLocalDeploymentStatus_deploymentId_not_exist() throws Exception {
        GetLocalDeploymentStatusRequest request =
                GetLocalDeploymentStatusRequest.builder().deploymentId(MOCK_DEPLOYMENT_ID).build();
        Topics mockServiceConfig = mock(Topics.class);
        Topics mockLocalDeployments = mock(Topics.class);
        when(mockServiceConfig.findTopics(eq(PERSISTENT_LOCAL_DEPLOYMENTS))).thenReturn(mockLocalDeployments);
        try {
            cliServiceAgent.getLocalDeploymentStatus(mockServiceConfig, request);
        } catch (ResourceNotFoundError e) {
            assertEquals(LOCAL_DEPLOYMENT_RESOURCE, e.getResourceType());
            assertEquals(MOCK_DEPLOYMENT_ID, e.getResourceName());
        }
    }

    @Test
    void testListLocalDeployments_success() throws Exception {
        Topics mockServiceConfig = mock(Topics.class);
        Topics mockLocalDeployments = Topics.of(context, PERSISTENT_LOCAL_DEPLOYMENTS, null);
        Map<String, Object> deploymentDetails = new HashMap<>();
        deploymentDetails.put(DEPLOYMENT_STATUS_KEY_NAME, DeploymentStatus.IN_PROGRESS.toString());
        Map<String, Object> deploymentDetails2 = new HashMap<>();
        deploymentDetails2.put(DEPLOYMENT_STATUS_KEY_NAME,
                DeploymentStatus.IN_PROGRESS.toString());
        Topics mockDeploymentTopic = mockLocalDeployments.lookupTopics(MOCK_DEPLOYMENT_ID);
        mockDeploymentTopic.replaceAndWait(deploymentDetails);
        Topics mockDeploymentTopic2 = mockLocalDeployments.lookupTopics(MOCK_DEPLOYMENT_ID2);
        mockDeploymentTopic2.replaceAndWait(deploymentDetails2);
        when(mockServiceConfig.findTopics(eq(PERSISTENT_LOCAL_DEPLOYMENTS))).thenReturn(mockLocalDeployments);
        ListLocalDeploymentResponse response = cliServiceAgent.listLocalDeployments(mockServiceConfig);
        LocalDeployment expectedLocalDeployment = LocalDeployment.builder().deploymentId(MOCK_DEPLOYMENT_ID)
                .status(DeploymentStatus.IN_PROGRESS).build();
        assertThat(response.getLocalDeployments(), hasItem(expectedLocalDeployment));
    }

    private GreengrassService createMockService(String componentName, State state, String version,
                                                Map<String, Object> parameters) {
        GreengrassService mockService = mock(GreengrassService.class);
        when(mockService.getState()).thenReturn(state);
        when(mockService.getName()).thenReturn(componentName);
        Topics mockTopics = mock(Topics.class);
        Topic mockTopic = mock(Topic.class);
        when(mockTopic.getOnce()).thenReturn(version);
        when(mockTopics.find(VERSION_CONFIG_KEY)).thenReturn(mockTopic);
        when(mockService.getServiceConfig()).thenReturn(mockTopics);
        if (parameters != null) {
            Topics mockParameters = mock(Topics.class);
            when(mockParameters.toPOJO()).thenReturn(parameters);
            when(mockTopics.findInteriorChild(eq(CONFIGURATION_CONFIG_KEY))).thenReturn(mockParameters);
        }
        return mockService;
    }
}
