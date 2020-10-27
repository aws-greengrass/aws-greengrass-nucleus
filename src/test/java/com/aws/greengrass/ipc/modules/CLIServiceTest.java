/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.builtin.services.cli.CLIEventStreamAgent;
import com.aws.greengrass.builtin.services.cli.CLIServiceAgent;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.IPCRouter;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.services.cli.CliClientOpCodes;
import com.aws.greengrass.ipc.services.cli.exceptions.ComponentNotFoundError;
import com.aws.greengrass.ipc.services.cli.exceptions.GenericCliIpcServerException;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidArgumentsError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidArtifactsDirectoryPathError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidRecipesDirectoryPathError;
import com.aws.greengrass.ipc.services.cli.exceptions.ResourceNotFoundError;
import com.aws.greengrass.ipc.services.cli.exceptions.ServiceError;
import com.aws.greengrass.ipc.services.cli.models.CliGenericResponse;
import com.aws.greengrass.ipc.services.cli.models.ComponentDetails;
import com.aws.greengrass.ipc.services.cli.models.CreateLocalDeploymentRequest;
import com.aws.greengrass.ipc.services.cli.models.CreateLocalDeploymentResponse;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsRequest;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsResponse;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusRequest;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusResponse;
import com.aws.greengrass.ipc.services.cli.models.ListComponentsResponse;
import com.aws.greengrass.ipc.services.cli.models.ListLocalDeploymentResponse;
import com.aws.greengrass.ipc.services.cli.models.LocalDeployment;
import com.aws.greengrass.ipc.services.cli.models.RequestStatus;
import com.aws.greengrass.ipc.services.cli.models.RestartComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.RestartComponentResponse;
import com.aws.greengrass.ipc.services.cli.models.StopComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.StopComponentResponse;
import com.aws.greengrass.ipc.services.cli.models.UpdateRecipesAndArtifactsRequest;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Group;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.exceptions.misusing.InvalidUseOfMatchersException;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.aws.greengrass.builtin.services.cli.CLIServiceAgent.LOCAL_DEPLOYMENT_RESOURCE;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH;
import static com.aws.greengrass.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static com.aws.greengrass.ipc.modules.CLIService.CLI_AUTH_TOKEN;
import static com.aws.greengrass.ipc.modules.CLIService.CLI_SERVICE;
import static com.aws.greengrass.ipc.modules.CLIService.DOMAIN_SOCKET_PATH;
import static com.aws.greengrass.ipc.modules.CLIService.OBJECT_MAPPER;
import static com.aws.greengrass.ipc.modules.CLIService.SOCKET_URL;
import static com.aws.greengrass.ipc.modules.CLIService.posixGroups;
import static com.aws.greengrass.ipc.services.cli.models.CliGenericResponse.MessageType.APPLICATION_ERROR;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
@SuppressWarnings("PMD.CouplingBetweenObjects")
class CLIServiceTest extends GGServiceTestUtil {

    private static final String SERVICEA = "ServiceA";
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();
    private static final String MOCK_DEPLOYMENT_ID = "MockId";
    private static final String MOCK_AUTH_TOKEN = "CliAuthToken";
    private static final Object MOCK_SOCKET_URL = "tcp://127.0.0.1:5667";

    @Mock
    private CLIServiceAgent agent;
    @Mock
    private IPCRouter router;
    @Mock
    private DeploymentStatusKeeper deploymentStatusKeeper;
    @Mock
    private AuthenticationHandler authenticationHandler;
    @Mock
    private Kernel kernel;
    @Mock
    private NucleusPaths nucleusPaths;
    @Mock
    private GreengrassCoreIPCService greengrassCoreIPCService;
    @Mock
    private CLIEventStreamAgent cliEventStreamAgent;
    @TempDir
    Path tmpDir;

    private ConnectionContext connectionContext;
    private CLIService cliService;
    private Topics serviceConfigSpy;
    private Topics cliConfigSpy;
    private Topics privateConfigSpy;

    @BeforeEach
    void setup() {
        lenient().when(kernel.getNucleusPaths()).thenReturn(nucleusPaths);
        serviceFullName = CLI_SERVICE;
        initializeMockedConfig();
        serviceConfigSpy = spy(Topics.of(context, SERVICES_NAMESPACE_TOPIC, null));
        cliConfigSpy = spy(Topics.of(context, CLI_SERVICE, serviceConfigSpy));
        privateConfigSpy = spy(Topics.of(context, PRIVATE_STORE_NAMESPACE_TOPIC, cliConfigSpy));

        cliService = new CLIService(cliConfigSpy, privateConfigSpy, router, agent, cliEventStreamAgent,
                deploymentStatusKeeper, authenticationHandler, kernel, greengrassCoreIPCService);
        cliService.postInject();
        connectionContext = new ConnectionContext(SERVICEA, new InetSocketAddress(1), router);
    }

    @Test
    void testPostInject_calls_made() throws Exception {
        verify(router).registerServiceCallback(eq(BuiltInServiceDestinationCode.CLI.getValue()), any());
        verify(deploymentStatusKeeper).registerDeploymentStatusConsumer(eq(Deployment.DeploymentType.LOCAL), any(),
                eq(CLIService.class.getName()));
        verify(cliConfigSpy).lookup(PARAMETERS_CONFIG_KEY, posixGroups);
    }

    @Test
    void testStartup_default_auth() throws Exception {
        when(authenticationHandler.registerAuthenticationTokenForExternalClient(anyString(), anyString())).thenReturn(
                MOCK_AUTH_TOKEN);
        when(nucleusPaths.cliIpcInfoPath()).thenReturn(tmpDir);
        Topic mockSocketUrlTopic = mock(Topic.class);
        when(mockSocketUrlTopic.getOnce()).thenReturn(MOCK_SOCKET_URL);
        Topics mockRootTopics = mock(Topics.class);
        when(mockRootTopics.find(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME))
                .thenReturn(mockSocketUrlTopic);
        when(mockRootTopics.find(SETENV_CONFIG_NAMESPACE, NUCLEUS_DOMAIN_SOCKET_FILEPATH))
                .thenReturn(mockSocketUrlTopic);
        when(cliConfigSpy.getRoot()).thenReturn(mockRootTopics);
        cliService.startup();
        verifyHandlersRegisteredForAllOperations();
        verify(authenticationHandler).registerAuthenticationTokenForExternalClient
                (anyString(), startsWith("greengrass-cli-user"));

        Path authDir = nucleusPaths.cliIpcInfoPath();
        assertTrue(Files.exists(authDir));
        File[] files = authDir.toFile().listFiles();
        assertEquals(1, files.length);

        Map<String, String> ipcInfo = OBJECT_MAPPER.readValue(Files.readAllBytes(files[0].toPath()), Map.class);
        assertEquals(MOCK_SOCKET_URL, ipcInfo.get(SOCKET_URL));
        assertEquals(MOCK_AUTH_TOKEN, ipcInfo.get(CLI_AUTH_TOKEN));
    }

    @Test
    void testStartup_group_auth(ExtensionContext context) throws Exception {
        if (Exec.isWindows) {
            // TODO support group auth on Windows
            return;
        }
        ignoreExceptionOfType(context, UserPrincipalNotFoundException.class);

        String MOCK_AUTH_TOKEN_2 = "CliAuthToken2";
        when(authenticationHandler.registerAuthenticationTokenForExternalClient(anyString(), anyString()))
                .thenAnswer(i -> {
                    Object clientId = i.getArgument(1);
                    if ("greengrass-cli-group-123".equals(clientId)) {
                        return MOCK_AUTH_TOKEN;
                    } else if ("greengrass-cli-group-456".equals(clientId)) {
                        return MOCK_AUTH_TOKEN_2;
                    }
                    throw new InvalidUseOfMatchersException(
                            String.format("Argument %s does not match", clientId)
                    );
                });
        when(nucleusPaths.cliIpcInfoPath()).thenReturn(tmpDir);
        Topic mockSocketUrlTopic = mock(Topic.class);
        when(mockSocketUrlTopic.getOnce()).thenReturn(MOCK_SOCKET_URL);
        Topics mockRootTopics = mock(Topics.class);
        when(mockRootTopics.find(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME))
                .thenReturn(mockSocketUrlTopic);
        when(mockRootTopics.find(SETENV_CONFIG_NAMESPACE, NUCLEUS_DOMAIN_SOCKET_FILEPATH))
                .thenReturn(mockSocketUrlTopic);
        when(cliConfigSpy.getRoot()).thenReturn(mockRootTopics);

        Topic mockPosixGroupsTopic = mock(Topic.class);
        when(mockPosixGroupsTopic.getOnce()).thenReturn("ubuntu,123,someone");
        when(cliConfigSpy.find(PARAMETERS_CONFIG_KEY, posixGroups)).thenReturn(mockPosixGroupsTopic);

        CLIService cliServiceSpy = spy(cliService);
        Group groupUbuntu = new Group("ubuntu", 123);
        doAnswer(i -> {
            Object argument = i.getArgument(0);
            if ("ubuntu".equals(argument) || "123".equals(argument)) {
                return groupUbuntu;
            } else if ("someone".equals(argument)) {
                return new Group("someone", 456);
            }
            throw new InvalidUseOfMatchersException(
                    String.format("Argument %s does not match", argument)
            );
        }).when(cliServiceSpy).getGroup(anyString());
        cliServiceSpy.startup();

        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        verify(authenticationHandler, times(2)).registerAuthenticationTokenForExternalClient
                (anyString(), argument.capture());
        assertThat(argument.getAllValues(), containsInRelativeOrder("greengrass-cli-group-123",
                "greengrass-cli-group-456"));

        Path authDir = nucleusPaths.cliIpcInfoPath();
        assertTrue(Files.exists(authDir.resolve("group-123")));
        assertTrue(Files.exists(authDir.resolve("group-456")));
        assertEquals(2, authDir.toFile().listFiles().length);

        Map<String, String> ipcInfo =
                OBJECT_MAPPER.readValue(Files.readAllBytes(authDir.resolve("group-123")), Map.class);
        assertEquals(MOCK_SOCKET_URL, ipcInfo.get(SOCKET_URL));
        assertEquals(MOCK_AUTH_TOKEN, ipcInfo.get(CLI_AUTH_TOKEN));

        ipcInfo = OBJECT_MAPPER.readValue(Files.readAllBytes(authDir.resolve("group-456")), Map.class);
        assertEquals(MOCK_SOCKET_URL, ipcInfo.get(SOCKET_URL));
        assertEquals(MOCK_AUTH_TOKEN_2, ipcInfo.get(CLI_AUTH_TOKEN));
        assertEquals(MOCK_SOCKET_URL, ipcInfo.get(DOMAIN_SOCKET_PATH));
    }

    private void verifyHandlersRegisteredForAllOperations() {
        OperationContinuationHandlerContext mockContext = mock(OperationContinuationHandlerContext.class);
        ArgumentCaptor<Function> argumentCaptor = ArgumentCaptor.forClass(Function.class);
        verify(greengrassCoreIPCService).setGetComponentDetailsHandler(argumentCaptor.capture());
        verify(greengrassCoreIPCService).setListComponentsHandler(argumentCaptor.capture());
        argumentCaptor.getAllValues().stream().forEach(handler -> handler.apply(mockContext));
        verify(cliEventStreamAgent).getGetComponentDetailsHandler(mockContext);
        verify(cliEventStreamAgent).getListComponentsHandler(mockContext);
    }

    @Test
    void testDeploymentStatusChanged_calls() {
        Map<String, Object> deploymentDetails = new HashMap<>();
        cliService.deploymentStatusChanged(deploymentDetails);
        verify(cliEventStreamAgent).persistLocalDeployment(cliConfigSpy, deploymentDetails);
    }

    @Test
    void testGetComponentsCall_success() throws Exception {
        GetComponentDetailsRequest request = GetComponentDetailsRequest.builder().build();
        GetComponentDetailsResponse response =
                GetComponentDetailsResponse.builder()
                        .componentDetails(ComponentDetails.builder().componentName(SERVICEA).build())
                        .build();
        when(agent.getComponentDetails(eq(request))).thenReturn(response);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.GET_COMPONENT_DETAILS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GetComponentDetailsResponse actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GetComponentDetailsResponse.class);
        assertEquals(SERVICEA, actualResponse.getComponentDetails().getComponentName());
    }

    @Test
    void testGetComponentsCall_ComponentNotFoundError() throws Exception {
        GetComponentDetailsRequest request = GetComponentDetailsRequest.builder().build();
        ComponentNotFoundError error = new ComponentNotFoundError("Error");
        when(agent.getComponentDetails(eq(request))).thenThrow(error);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.GET_COMPONENT_DETAILS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(ComponentNotFoundError.class.getSimpleName(), actualResponse.getErrorType());
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
    }

    @Test
    void testGetComponentsCall_InvalidArgumentsError() throws Exception {
        GetComponentDetailsRequest request = GetComponentDetailsRequest.builder().build();
        InvalidArgumentsError error = new InvalidArgumentsError("Invalid component name");
        when(agent.getComponentDetails(eq(request))).thenThrow(error);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.GET_COMPONENT_DETAILS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(InvalidArgumentsError.class.getSimpleName(), actualResponse.getErrorType());
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
    }

    @Test
    void testGetComponentsCall_ServiceException(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);
        GetComponentDetailsRequest request = GetComponentDetailsRequest.builder().build();
        RuntimeException error = new RuntimeException("Runtime error");
        when(agent.getComponentDetails(eq(request))).thenThrow(error);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.GET_COMPONENT_DETAILS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(ServiceError.class.getSimpleName(), actualResponse.getErrorType());
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
    }

    @Test
    void testListComponentsCall_success() throws Exception {
        List<ComponentDetails> listOfComponents = new ArrayList<>();
        ListComponentsResponse response =
                ListComponentsResponse.builder()
                        .components(listOfComponents)
                        .build();
        when(agent.listComponents()).thenReturn(response);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.LIST_COMPONENTS.ordinal())
                        .payload("".getBytes()).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        ListComponentsResponse actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                ListComponentsResponse.class);
        assertEquals(listOfComponents, actualResponse.getComponents());
        assertEquals(CliGenericResponse.MessageType.APPLICATION_MESSAGE, actualResponse.getMessageType());
    }

    @Test
    void testListComponentsCall_with_runtime_exception(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);
        when(agent.listComponents()).thenThrow(new RuntimeException());
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.LIST_COMPONENTS.ordinal())
                        .payload("".getBytes()).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ServiceError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testRestartComponentsCall_success() throws Exception {
        RestartComponentRequest request = RestartComponentRequest.builder().build();
        RestartComponentResponse response =
                RestartComponentResponse.builder().requestStatus(RequestStatus.SUCCEEDED).build();
        when(agent.restartComponent(eq(request))).thenReturn(response);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.RESTART_COMPONENT.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        RestartComponentResponse actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                RestartComponentResponse.class);
        assertEquals(RequestStatus.SUCCEEDED, actualResponse.getRequestStatus());
    }

    @Test
    void testRestartComponentsCall_with_component_not_exists() throws Exception {
        RestartComponentRequest request = RestartComponentRequest.builder().build();
        ComponentNotFoundError error = new ComponentNotFoundError("Error");
        when(agent.restartComponent(eq(request))).thenThrow(error);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.RESTART_COMPONENT.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ComponentNotFoundError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testRestartComponentsCall_with_runtime_exception(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);
        RestartComponentRequest request = RestartComponentRequest.builder().build();
        RuntimeException error = new RuntimeException("Error");
        when(agent.restartComponent(eq(request))).thenThrow(error);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.RESTART_COMPONENT.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ServiceError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testStopComponentsCall_success() throws Exception {
        StopComponentRequest request = StopComponentRequest.builder().build();
        StopComponentResponse response =
                StopComponentResponse.builder().requestStatus(RequestStatus.SUCCEEDED)
                        .build();
        when(agent.stopComponent(eq(request))).thenReturn(response);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.STOP_COMPONENT.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        StopComponentResponse actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                StopComponentResponse.class);
        assertEquals(CliGenericResponse.MessageType.APPLICATION_MESSAGE, actualResponse.getMessageType());
        assertEquals(RequestStatus.SUCCEEDED, actualResponse.getRequestStatus());
    }

    @Test
    void testStopComponentsCall_with_component_not_exists() throws Exception {
        StopComponentRequest request = StopComponentRequest.builder().build();
        ComponentNotFoundError error = new ComponentNotFoundError("error");
        when(agent.stopComponent(eq(request))).thenThrow(error);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.STOP_COMPONENT.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ComponentNotFoundError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testStopComponentsCall_with_runtime_exception(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);
        StopComponentRequest request = StopComponentRequest.builder().build();
        RuntimeException error = new RuntimeException("error");
        when(agent.stopComponent(eq(request))).thenThrow(error);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.STOP_COMPONENT.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ServiceError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testUpdateRecipesCall_success() throws Exception {
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder().build();
        doNothing().when(agent).updateRecipesAndArtifacts(eq(request));
        FrameReader.Message message = cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.UPDATE_RECIPES_AND_ARTIFACTS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        CliGenericResponse actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                CliGenericResponse.class);
        assertEquals(CliGenericResponse.MessageType.APPLICATION_MESSAGE, actualResponse.getMessageType());
    }

    @Test
    void testUpdateRecipesCall_invalidArguments() throws Exception {
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder().build();
        doThrow(new InvalidArgumentsError("Error")).when(agent).updateRecipesAndArtifacts(eq(request));
        FrameReader.Message message = cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.UPDATE_RECIPES_AND_ARTIFACTS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(InvalidArgumentsError.class.getSimpleName(), actualResponse.getErrorType());
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
    }

    @Test
    void testUpdateRecipesCall_invalidRecipePathError() throws Exception {
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder().build();
        doThrow(new InvalidRecipesDirectoryPathError("Error")).when(agent).updateRecipesAndArtifacts(eq(request));
        FrameReader.Message message = cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.UPDATE_RECIPES_AND_ARTIFACTS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(InvalidRecipesDirectoryPathError.class.getSimpleName(), actualResponse.getErrorType());
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
    }

    @Test
    void testUpdateRecipesCall_invalidArtifactsPathError() throws Exception {
        UpdateRecipesAndArtifactsRequest request = UpdateRecipesAndArtifactsRequest.builder().build();
        doThrow(new InvalidArtifactsDirectoryPathError("Error")).when(agent).updateRecipesAndArtifacts(eq(request));
        FrameReader.Message message = cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.UPDATE_RECIPES_AND_ARTIFACTS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(InvalidArtifactsDirectoryPathError.class.getSimpleName(), actualResponse.getErrorType());
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
    }

    @Test
    void testCreateLocalDeploymentCall_success() throws Exception {
        CreateLocalDeploymentRequest request = CreateLocalDeploymentRequest.builder().build();
        CreateLocalDeploymentResponse response =
                CreateLocalDeploymentResponse.builder()
                        .deploymentId(MOCK_DEPLOYMENT_ID)
                        .build();
        when(agent.createLocalDeployment(eq(cliConfigSpy), eq(request))).thenReturn(response);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.CREATE_LOCAL_DEPLOYMENT.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        CreateLocalDeploymentResponse actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                CreateLocalDeploymentResponse.class);
        assertEquals(MOCK_DEPLOYMENT_ID, actualResponse.getDeploymentId());
    }

    @Test
    void testCreateLocalDeploymentCall_runtimeException(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);
        CreateLocalDeploymentRequest request = CreateLocalDeploymentRequest.builder().build();

        when(agent.createLocalDeployment(eq(cliConfigSpy), eq(request))).thenThrow(new RuntimeException("Error"));
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.CREATE_LOCAL_DEPLOYMENT.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ServiceError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testGetLocalDeploymentCall_success() throws Exception {
        GetLocalDeploymentStatusRequest request = GetLocalDeploymentStatusRequest.builder().build();
        GetLocalDeploymentStatusResponse response =
                GetLocalDeploymentStatusResponse.builder()
                        .deployment(LocalDeployment.builder().deploymentId(MOCK_DEPLOYMENT_ID)
                                .status(DeploymentStatus.SUCCEEDED).build())
                        .build();
        when(agent.getLocalDeploymentStatus(cliConfigSpy, request)).thenReturn(response);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.GET_LOCAL_DEPLOYMENT_STATUS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GetLocalDeploymentStatusResponse actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GetLocalDeploymentStatusResponse.class);
        assertEquals(DeploymentStatus.SUCCEEDED, actualResponse.getDeployment().getStatus());
    }

    @Test
    void testGetLocalDeploymentCall_deploymentId_invalid_format() throws Exception {
        GetLocalDeploymentStatusRequest request = GetLocalDeploymentStatusRequest.builder().build();

        when(agent.getLocalDeploymentStatus(cliConfigSpy, request))
                .thenThrow(new InvalidArgumentsError("DeploymentId is not UUID"));
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.GET_LOCAL_DEPLOYMENT_STATUS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(InvalidArgumentsError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testGetLocalDeploymentCall_deploymentId_not_found() throws Exception {
        GetLocalDeploymentStatusRequest request = GetLocalDeploymentStatusRequest.builder().build();
        ResourceNotFoundError error = new ResourceNotFoundError("Deployment not found",
                LOCAL_DEPLOYMENT_RESOURCE, MOCK_DEPLOYMENT_ID);
        when(agent.getLocalDeploymentStatus(cliConfigSpy, request))
                .thenThrow(error);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.GET_LOCAL_DEPLOYMENT_STATUS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ResourceNotFoundError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testGetLocalDeploymentCall_runtimeException(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);
        GetLocalDeploymentStatusRequest request = GetLocalDeploymentStatusRequest.builder().build();

        when(agent.getLocalDeploymentStatus(cliConfigSpy, request)).thenThrow(new RuntimeException("Error"));
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.GET_LOCAL_DEPLOYMENT_STATUS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ServiceError.class.getSimpleName(), actualResponse.getErrorType());
    }

    @Test
    void testListLocalDeploymentCall_success() throws Exception {
        GetComponentDetailsRequest request = GetComponentDetailsRequest.builder().build();
        List<LocalDeployment> listOflocalDeployments = new ArrayList<>();
        ListLocalDeploymentResponse response =
                ListLocalDeploymentResponse.builder()
                        .localDeployments(listOflocalDeployments)
                        .build();
        when(agent.listLocalDeployments(cliConfigSpy)).thenReturn(response);
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.LIST_LOCAL_DEPLOYMENTS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        ListLocalDeploymentResponse actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                ListLocalDeploymentResponse.class);
        assertEquals(listOflocalDeployments, actualResponse.getLocalDeployments());
    }

    @Test
    void testListLocalDeploymentCall_runtime_exception(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RuntimeException.class);
        GetComponentDetailsRequest request = GetComponentDetailsRequest.builder().build();
        when(agent.listLocalDeployments(cliConfigSpy)).thenThrow(new RuntimeException("Error"));
        FrameReader.Message message =  cliService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(CliClientOpCodes.LIST_LOCAL_DEPLOYMENTS.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        GenericCliIpcServerException actualResponse = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                GenericCliIpcServerException.class);
        assertEquals(APPLICATION_ERROR, actualResponse.getMessageType());
        assertEquals(ServiceError.class.getSimpleName(), actualResponse.getErrorType());
    }
}
