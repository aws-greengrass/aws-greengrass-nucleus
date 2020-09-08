package com.aws.iot.evergreen.ipc.modules;

import com.aws.iot.evergreen.builtin.services.cli.CLIServiceAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.deployment.DeploymentStatusKeeper;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.ipc.AuthenticationHandler;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
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
import com.aws.iot.evergreen.ipc.services.cli.models.CreateLocalDeploymentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.DeploymentStatus;
import com.aws.iot.evergreen.ipc.services.cli.models.GetComponentDetailsRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.GetLocalDeploymentStatusRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.RestartComponentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.StopComponentRequest;
import com.aws.iot.evergreen.ipc.services.cli.models.UpdateRecipesAndArtifactsRequest;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.PluginService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import lombok.Data;
import lombok.ToString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.iot.evergreen.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;

@ImplementsService(name = CLIService.CLI_SERVICE, autostart = true)
@ToString
public class CLIService extends PluginService {

    public static final String GREENGRASS_CLI = "greengrass-cli";
    public static final String CLI_SERVICE = "aws.greengrass.ipc.cli";

    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();
    protected static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String CLI_AUTH_TOKEN = "cli_auth_token";
    private static final String SOCKET_URL = "socket_url";



    @Inject
    CLIServiceAgent agent;

    @Inject
    private IPCRouter router;

    @Inject
    private DeploymentStatusKeeper deploymentStatusKeeper;

    @Inject
    private AuthenticationHandler authenticationHandler;

    @Inject
    private Kernel kernel;

    public CLIService(Topics topics) {
        super(topics);
    }

    /**
     * Constructor for unit testing.
     * @param topics Service config
     * @param privateConfig Private config for the service
     * @param router {@link IPCRouter}
     * @param agent {@link CLIServiceAgent}
     * @param deploymentStatusKeeper {@link DeploymentStatusKeeper}
     * @param authenticationHandler {@link AuthenticationHandler}
     * @param kernel {@link Kernel}
     */
    public CLIService(Topics topics, Topics privateConfig, IPCRouter router, CLIServiceAgent agent,
                      DeploymentStatusKeeper deploymentStatusKeeper, AuthenticationHandler authenticationHandler,
                      Kernel kernel) {
        super(topics, privateConfig);
        this.router = router;
        this.agent = agent;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.authenticationHandler = authenticationHandler;
        this.kernel = kernel;
    }

    @Override
    public void postInject() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.CLI;
        super.postInject();
        // Does not happen for PluginService so doing explicitly
        AuthenticationHandler.registerAuthenticationToken(this);
        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo().setEventType("ipc-register-request-handler").addKeyValue("destination", destination.name())
                    .log();
            deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL,
                    this::deploymentStatusChanged, CLIService.class.getName());
            generateCliIpcInfo();
        } catch (IPCException e) {
            logger.atError().setEventType("ipc-register-request-handler-error").setCause(e)
                    .addKeyValue("destination", destination.name())
                    .log("Failed to register service callback to destination");
        } catch (IOException e) {
            logger.atError().setEventType("cli-ipc-info-generation-error").setCause(e)
                    .log("Failed to create cli_ipc_info file");
        }
    }

    private void generateCliIpcInfo() throws IOException, UnauthenticatedException {
        String cliAuthToken = authenticationHandler.registerAuthenticationTokenForExternalClient(
                getPrivateConfig().find(SERVICE_UNIQUE_ID_KEY).getOnce().toString(),
                GREENGRASS_CLI);
        // Write CLI auth token to the files
        Path filePath = kernel.getRootPath().resolve("cli_ipc_info");
        Map<String, String> ipcInfo = new HashMap<>();
        //TODO: Change the URL as per the new IPC
        ipcInfo.put(SOCKET_URL, System.getenv(KERNEL_URI_ENV_VARIABLE_NAME));
        ipcInfo.put(CLI_AUTH_TOKEN, cliAuthToken);

        Files.write(filePath, OBJECT_MAPPER.writeValueAsString(ipcInfo).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        // TODO: Add the users in configuration to the group
        Files.setPosixFilePermissions(filePath,
                new HashSet<PosixFilePermission>(Arrays.asList(PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ)));
    }

    @Data
    public static class LocalDeploymentDetails {
        String deploymentId;
        DeploymentStatus status;
    }

    @SuppressWarnings("PMD.EmptyIfStmt")
    protected Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        agent.persistLocalDeployment(config, deploymentDetails);
        return true;
    }

    /**
     * Handle all requests for CLI from the CLI client.
     *
     * @param message incoming request
     * @param context Context identifying the client and the channel
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Future<FrameReader.Message> handleMessage(FrameReader.Message message, ConnectionContext context) {
        CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();

        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        try {
            //TODO: add version compatibility check
            CliClientOpCodes opCode = CliClientOpCodes.values()[applicationMessage.getOpCode()];
            ApplicationMessage responseMessage = null;
            switch (opCode) {
                case GET_COMPONENT_DETAILS: {

                    GetComponentDetailsRequest request = CBOR_MAPPER
                            .readValue(applicationMessage.getPayload(), GetComponentDetailsRequest.class);
                    try {
                        CliGenericResponse cliGenericResponse = agent.getComponentDetails(request);
                        responseMessage = getSuccessfulResponseMessage(cliGenericResponse, applicationMessage
                                .getVersion());
                    } catch (InvalidArgumentsError | ComponentNotFoundError e) {
                        responseMessage = getErrorResponseMessage(e, applicationMessage.getVersion());
                    }
                    break;
                }
                case LIST_COMPONENTS: {
                    CliGenericResponse cliGenericResponse = agent.listComponents();
                    responseMessage = getSuccessfulResponseMessage(cliGenericResponse, applicationMessage.getVersion());
                    break;
                }
                case RESTART_COMPONENT: {
                    RestartComponentRequest request = CBOR_MAPPER
                            .readValue(applicationMessage.getPayload(), RestartComponentRequest.class);
                    try {
                        CliGenericResponse cliGenericResponse = agent.restartComponent(request);
                        responseMessage = getSuccessfulResponseMessage(cliGenericResponse, applicationMessage
                                .getVersion());
                    } catch (InvalidArgumentsError | ComponentNotFoundError e) {
                        responseMessage = getErrorResponseMessage(e, applicationMessage.getVersion());
                    }
                    break;
                }
                case STOP_COMPONENT: {
                    StopComponentRequest request = CBOR_MAPPER
                            .readValue(applicationMessage.getPayload(), StopComponentRequest.class);
                    try {
                        CliGenericResponse cliGenericResponse = agent.stopComponent(request);
                        responseMessage = getSuccessfulResponseMessage(cliGenericResponse, applicationMessage
                                .getVersion());
                    } catch (InvalidArgumentsError | ComponentNotFoundError e) {
                        responseMessage = getErrorResponseMessage(e, applicationMessage.getVersion());
                    }
                    break;
                }
                case UPDATE_RECIPES_AND_ARTIFACTS: {
                    UpdateRecipesAndArtifactsRequest request = CBOR_MAPPER
                            .readValue(applicationMessage.getPayload(), UpdateRecipesAndArtifactsRequest.class);
                    try {
                        agent.updateRecipesAndArtifacts(request);
                        CliGenericResponse cliGenericResponse = new CliGenericResponse();
                        cliGenericResponse.setMessageType(CliGenericResponse.MessageType.APPLICATION_MESSAGE);
                        responseMessage = getSuccessfulResponseMessage(cliGenericResponse,
                                applicationMessage.getVersion());
                    } catch (InvalidArgumentsError | InvalidRecipesDirectoryPathError
                            | InvalidArtifactsDirectoryPathError e) {
                        responseMessage = getErrorResponseMessage(e, applicationMessage.getVersion());
                    }
                    break;
                }
                case CREATE_LOCAL_DEPLOYMENT: {
                    CreateLocalDeploymentRequest request = CBOR_MAPPER
                            .readValue(applicationMessage.getPayload(), CreateLocalDeploymentRequest.class);
                    CliGenericResponse cliGenericResponse = agent.createLocalDeployment(config, request);
                    responseMessage = getSuccessfulResponseMessage(cliGenericResponse, applicationMessage.getVersion());
                    break;
                }
                case GET_LOCAL_DEPLOYMENT_STATUS: {
                    GetLocalDeploymentStatusRequest request = CBOR_MAPPER
                            .readValue(applicationMessage.getPayload(), GetLocalDeploymentStatusRequest.class);
                    try {
                        CliGenericResponse cliGenericResponse = agent.getLocalDeploymentStatus(config, request);
                        responseMessage = getSuccessfulResponseMessage(cliGenericResponse,
                                applicationMessage.getVersion());
                    } catch (InvalidArgumentsError | ResourceNotFoundError e) {
                        responseMessage = getErrorResponseMessage(e, applicationMessage.getVersion());
                    }
                    break;
                }
                case LIST_LOCAL_DEPLOYMENTS: {
                    CliGenericResponse cliGenericResponse = agent.listLocalDeployments(config);
                    responseMessage = getSuccessfulResponseMessage(cliGenericResponse, applicationMessage.getVersion());
                    break;
                }
                default:
                    CliGenericResponse cliGenericResponse = new CliGenericResponse();
                    cliGenericResponse.setMessageType(CliGenericResponse.MessageType.APPLICATION_ERROR);
                    cliGenericResponse.setErrorType("Unknown request type " + opCode.toString());
                    responseMessage = ApplicationMessage.builder().version(applicationMessage.getVersion())
                            .payload(CBOR_MAPPER.writeValueAsBytes(cliGenericResponse)).build();
                    break;
            }

            fut.complete(new FrameReader.Message(responseMessage.toByteArray()));
        } catch (Throwable e) {
            logger.atError().setEventType("cli-ipc-error").setCause(e).log("Failed to handle message");
            try {
                ServiceError error = new ServiceError(e.getMessage());
                ApplicationMessage responseMessage = getErrorResponseMessage(error, applicationMessage.getVersion());
                fut.complete(new FrameReader.Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError("cli-ipc-error", ex).log("Failed to send error response");
            }
        }
        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }
        return fut;
    }

    private ApplicationMessage getSuccessfulResponseMessage(CliGenericResponse cliGenericResponse, int version)
            throws JsonProcessingException {
        cliGenericResponse.setMessageType(CliGenericResponse.MessageType.APPLICATION_MESSAGE);
        return ApplicationMessage.builder().version(version).payload(CBOR_MAPPER.writeValueAsBytes(cliGenericResponse))
                .build();
    }

    private ApplicationMessage getErrorResponseMessage(GenericCliIpcServerException e, int version)
            throws JsonProcessingException {
        e.setMessageType(CliGenericResponse.MessageType.APPLICATION_ERROR);
        e.setErrorType(e.getClass().getSimpleName());
        return ApplicationMessage.builder().version(version).payload(CBOR_MAPPER.writeValueAsBytes(e)).build();
    }
}
