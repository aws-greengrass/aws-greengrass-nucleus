package com.aws.iot.evergreen.ipc.modules;

import com.aws.iot.evergreen.builtin.services.cli.CLIServiceAgent;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.DeploymentStatusKeeper;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.ipc.AuthenticationHandler;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.exceptions.UnauthenticatedException;
import com.aws.iot.evergreen.ipc.services.cli.CliClientOpCodes;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.ComponentNotFoundError;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.GenericCliIpcServerException;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.InvalidArgumentsError;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.InvalidArtifactsDirectoryPathError;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.InvalidRecipesDirectoryPathError;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.ResourceNotFoundError;
import com.aws.iot.evergreen.ipc.services.cli.exceptions.ServiceError;
import com.aws.iot.evergreen.ipc.services.cli.models.CliGenericResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.ComponentDetails;
import com.aws.iot.evergreen.ipc.services.cli.models.CreateLocalDeploymentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.CreateLocalDeploymentResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.DeploymentStatus;
import com.aws.iot.evergreen.ipc.services.cli.models.GetComponentDetailsRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.GetComponentDetailsResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.GetLocalDeploymentStatusRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.GetLocalDeploymentStatusResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.ListComponentsResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.ListLocalDeploymentResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.LocalDeployment;
import com.aws.iot.evergreen.ipc.services.cli.models.RequestStatus;
import com.aws.iot.evergreen.ipc.services.cli.models.RestartComponentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.RestartComponentResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.StopComponentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.StopComponentResponse;
import com.aws.iot.evergreen.ipc.services.cli.models.UpdateRecipesAndArtifactsRequest;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aws.iot.evergreen.builtin.services.cli.CLIServiceAgent.LOCAL_DEPLOYMENT_RESOURCE;
import static com.aws.iot.evergreen.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static com.aws.iot.evergreen.ipc.modules.CLIService.CLI_AUTH_TOKEN;
import static com.aws.iot.evergreen.ipc.modules.CLIService.CLI_IPC_INFO_FILENAME;
import static com.aws.iot.evergreen.ipc.modules.CLIService.CLI_SERVICE;
import static com.aws.iot.evergreen.ipc.modules.CLIService.GREENGRASS_CLI;
import static com.aws.iot.evergreen.ipc.modules.CLIService.OBJECT_MAPPER;
import static com.aws.iot.evergreen.ipc.modules.CLIService.SOCKET_URL;
import static com.aws.iot.evergreen.ipc.services.cli.models.CliGenericResponse.MessageType.APPLICATION_ERROR;
import static com.aws.iot.evergreen.kernel.EvergreenService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SETENV_CONFIG_NAMESPACE;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class CLIServiceTest extends EGServiceTestUtil {

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
    @TempDir
    Path kernelRootPath;

    private ConnectionContext connectionContext;
    private CLIService cliService;
    private Topics serviceConfigSpy;
    private Topics cliConfigSpy;
    private Topics privateConfigSpy;

    @BeforeEach
    public void setup() throws UnauthenticatedException {
        serviceFullName = CLI_SERVICE;
        initializeMockedConfig();
        serviceConfigSpy = spy(Topics.of(context, SERVICES_NAMESPACE_TOPIC, null));
        cliConfigSpy = spy(Topics.of(context, CLI_SERVICE, serviceConfigSpy));
        privateConfigSpy = spy(Topics.of(context, PRIVATE_STORE_NAMESPACE_TOPIC, cliConfigSpy));

        cliService = new CLIService(cliConfigSpy, privateConfigSpy, router, agent, deploymentStatusKeeper,
                authenticationHandler, kernel);
        cliService.postInject();
        connectionContext = new ConnectionContext(SERVICEA, new InetSocketAddress(1), router);
    }

    @Test
    public void testPostInject_calls_made() throws Exception {
        verify(router).registerServiceCallback(eq(BuiltInServiceDestinationCode.CLI.getValue()), any());
        verify(deploymentStatusKeeper).registerDeploymentStatusConsumer(eq(Deployment.DeploymentType.LOCAL), any(),
                eq(CLIService.class.getName()));
    }

    @Test
    public void testStartup() throws Exception {
        when(authenticationHandler.registerAuthenticationTokenForExternalClient(anyString(), anyString())).thenReturn(
                MOCK_AUTH_TOKEN);
        when(kernel.getRootPath()).thenReturn(kernelRootPath);
        Topic mockSocketUrlTopic = mock(Topic.class);
        when(mockSocketUrlTopic.getOnce()).thenReturn(MOCK_SOCKET_URL);
        Topics mockRootTopics = mock(Topics.class);
        when(mockRootTopics.find(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME))
                .thenReturn(mockSocketUrlTopic);
        when(cliConfigSpy.getRoot()).thenReturn(mockRootTopics);
        cliService.startup();
        verify(authenticationHandler).registerAuthenticationTokenForExternalClient
                (anyString(), eq(GREENGRASS_CLI));
        assertTrue(Files.exists(kernelRootPath.resolve(CLI_IPC_INFO_FILENAME)));
        Map<String, String> ipcInfo =
                OBJECT_MAPPER.readValue(Files.readAllBytes(kernelRootPath.resolve(CLI_IPC_INFO_FILENAME)),
                        Map.class);
        assertEquals(MOCK_SOCKET_URL, ipcInfo.get(SOCKET_URL));
        assertEquals(MOCK_AUTH_TOKEN, ipcInfo.get(CLI_AUTH_TOKEN));
    }

    @Test
    public void testDeploymentStatusChanged_calls() {
        Map<String, Object> deploymentDetails = new HashMap<>();
        cliService.deploymentStatusChanged(deploymentDetails);
        verify(agent).persistLocalDeployment(cliConfigSpy, deploymentDetails);
    }

    @Test
    public void testGetComponentsCall_success() throws Exception {
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
    public void testGetComponentsCall_ComponentNotFoundError() throws Exception {
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
    public void testGetComponentsCall_InvalidArgumentsError() throws Exception {
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
    public void testGetComponentsCall_ServiceException(ExtensionContext context) throws Exception {
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
    public void testListComponentsCall_success() throws Exception {
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
    public void testListComponentsCall_with_runtime_exception(ExtensionContext context) throws Exception {
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
    public void testRestartComponentsCall_success() throws Exception {
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
    public void testRestartComponentsCall_with_component_not_exists() throws Exception {
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
    public void testRestartComponentsCall_with_runtime_exception(ExtensionContext context) throws Exception {
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
    public void testStopComponentsCall_success() throws Exception {
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
    public void testStopComponentsCall_with_component_not_exists() throws Exception {
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
    public void testStopComponentsCall_with_runtime_exception(ExtensionContext context) throws Exception {
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
    public void testUpdateRecipesCall_success() throws Exception {
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
    public void testUpdateRecipesCall_invalidArguments() throws Exception {
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
    public void testUpdateRecipesCall_invalidRecipePathError() throws Exception {
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
    public void testUpdateRecipesCall_invalidArtifactsPathError() throws Exception {
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
    public void testCreateLocalDeploymentCall_success() throws Exception {
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
    public void testCreateLocalDeploymentCall_runtimeException(ExtensionContext context) throws Exception {
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
    public void testGetLocalDeploymentCall_success() throws Exception {
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
    public void testGetLocalDeploymentCall_deploymentId_invalid_format() throws Exception {
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
    public void testGetLocalDeploymentCall_deploymentId_not_found() throws Exception {
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
    public void testGetLocalDeploymentCall_runtimeException(ExtensionContext context) throws Exception {
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
    public void testListLocalDeploymentCall_success() throws Exception {
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
    public void testListLocalDeploymentCall_runtime_exception(ExtensionContext context) throws Exception {
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
