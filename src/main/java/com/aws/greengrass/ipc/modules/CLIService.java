/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.builtin.services.cli.CLIEventStreamAgent;
import com.aws.greengrass.builtin.services.cli.CLIServiceAgent;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.IPCRouter;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.exceptions.IPCException;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.ipc.services.cli.CliClientOpCodes;
import com.aws.greengrass.ipc.services.cli.exceptions.ComponentNotFoundError;
import com.aws.greengrass.ipc.services.cli.exceptions.GenericCliIpcServerException;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidArgumentsError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidArtifactsDirectoryPathError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidRecipesDirectoryPathError;
import com.aws.greengrass.ipc.services.cli.exceptions.ResourceNotFoundError;
import com.aws.greengrass.ipc.services.cli.exceptions.ServiceError;
import com.aws.greengrass.ipc.services.cli.models.CliGenericResponse;
import com.aws.greengrass.ipc.services.cli.models.CreateLocalDeploymentRequest;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsRequest;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusRequest;
import com.aws.greengrass.ipc.services.cli.models.RestartComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.StopComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.UpdateRecipesAndArtifactsRequest;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.UserPlatform;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH;
import static com.aws.greengrass.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;

@ImplementsService(name = CLIService.CLI_SERVICE, autostart = true)
public class CLIService extends GreengrassService {

    public static final String GREENGRASS_CLI_CLIENT_ID_FMT = "greengrass-cli-%s";
    public static final String CLI_SERVICE = "aws.greengrass.ipc.cli";
    public static final String CLI_AUTH_TOKEN = "cli_auth_token";
    public static final String SOCKET_URL = "socket_url";
    public static final String posixGroups = "AuthorizedPosixGroups";

    static final String USER_CLIENT_ID_PREFIX = "user-";
    static final String GROUP_CLIENT_ID_PREFIX = "group-";
    static final FileSystemPermission DEFAULT_FILE_PERMISSION = new FileSystemPermission(null, null,
            true, true, false, false, false, false, false, false, false);
    public static final String DOMAIN_SOCKET_PATH = "domain_socket_path";

    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();
    protected static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Map<String, String> clientIdToAuthToken = new HashMap<>();

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

    @Inject
    private CLIEventStreamAgent cliEventStreamAgent;

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    public CLIService(Topics topics) {
        super(topics);
    }

    /**
     * Constructor for unit testing.
     * @param topics Service config
     * @param privateConfig Private config for the service
     * @param router {@link IPCRouter}
     * @param agent {@link CLIServiceAgent}
     * @param cliEventStreamAgent {@link CLIEventStreamAgent}
     * @param deploymentStatusKeeper {@link DeploymentStatusKeeper}
     * @param authenticationHandler {@link AuthenticationHandler}
     * @param kernel {@link Kernel}
     * @param greengrassCoreIPCService {@link GreengrassCoreIPCService}
     */
    public CLIService(Topics topics, Topics privateConfig, IPCRouter router, CLIServiceAgent agent,
                      CLIEventStreamAgent cliEventStreamAgent,
                      DeploymentStatusKeeper deploymentStatusKeeper, AuthenticationHandler authenticationHandler,
                      Kernel kernel, GreengrassCoreIPCService greengrassCoreIPCService) {
        super(topics, privateConfig);
        this.router = router;
        this.agent = agent;
        this.cliEventStreamAgent = cliEventStreamAgent;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.authenticationHandler = authenticationHandler;
        this.kernel = kernel;
        this.greengrassCoreIPCService = greengrassCoreIPCService;
    }

    @Override
    public void postInject() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.CLI;
        super.postInject();
        // Does not happen for built-in/plugin services so doing explicitly
        AuthenticationHandler.registerAuthenticationToken(this);
        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo().setEventType("ipc-register-request-handler").addKeyValue("destination", destination.name())
                    .log();
            deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL,
                    this::deploymentStatusChanged, CLIService.class.getName());

        } catch (IPCException e) {
            logger.atError().setEventType("ipc-register-request-handler-error").setCause(e)
                    .addKeyValue("destination", destination.name())
                    .log("Failed to register service callback to destination");
        }

        config.lookup(PARAMETERS_CONFIG_KEY, posixGroups).subscribe((why, newv) -> {
            requestRestart();
        });
    }

    private void registerIpcEventStreamHandlers() {
        greengrassCoreIPCService.setGetComponentDetailsHandler((context)
                -> cliEventStreamAgent.getGetComponentDetailsHandler(context));
        greengrassCoreIPCService.setListComponentsHandler((context)
                -> cliEventStreamAgent.getListComponentsHandler(context));
        greengrassCoreIPCService.setRestartComponentHandler((context)
                -> cliEventStreamAgent.getRestartComponentsHandler(context));
        greengrassCoreIPCService.setStopComponentHandler((context)
                -> cliEventStreamAgent.getStopComponentsHandler(context));
        greengrassCoreIPCService.setUpdateRecipesAndArtifactsHandler((context)
                -> cliEventStreamAgent.getUpdateRecipesAndArtifactsHandler(context));
        greengrassCoreIPCService.setCreateLocalDeploymentHandler((context)
                -> cliEventStreamAgent.getCreateLocalDeploymentHandler(context, config));
        greengrassCoreIPCService.setGetLocalDeploymentStatusHandler((context)
                -> cliEventStreamAgent.getGetLocalDeploymentStatusHandler(context, config));
        greengrassCoreIPCService.setListLocalDeploymentsHandler((context)
                -> cliEventStreamAgent.getListLocalDeploymentsHandler(context, config));
    }

    @Override
    protected void startup() throws InterruptedException {
        registerIpcEventStreamHandlers();
        try {
            generateCliIpcInfo();
            reportState(State.RUNNING);
        } catch (IOException | UnauthenticatedException e) {
            logger.atError().setEventType("cli-ipc-info-generation-error")
                    .setCause(e)
                    .log("Failed to create cli_ipc_info file");
            reportState(State.ERRORED);
        }
    }

    @Override
    protected void shutdown() {

    }

    String getClientIdForGroup(String groupId) {
        return GROUP_CLIENT_ID_PREFIX + groupId;
    }

    UserPlatform.BasicAttributes getGroup(String posixGroup) throws IOException {
        return Platform.getInstance().lookupGroupByName(posixGroup);
    }

    private synchronized void generateCliIpcInfo() throws UnauthenticatedException, IOException, InterruptedException {
        // GG_NEEDS_REVIEW: TODO: replace with the new IPC domain socket path
        if (config.getRoot().find(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME) == null) {
            logger.atWarn().log("Did not find IPC socket URL in the config. Not creating the cli ipc info file");
            return;
        }

        Path authTokenDir = kernel.getNucleusPaths().cliIpcInfoPath();
        revokeOutdatedAuthTokens(authTokenDir);

        if (Exec.isWindows) {
            // GG_NEEDS_REVIEW: TODO support windows group permissions
            generateCliIpcInfoForEffectiveUser(authTokenDir);
            return;
        }

        Topic authorizedPosixGroups = config.find(PARAMETERS_CONFIG_KEY, posixGroups);
        if (authorizedPosixGroups == null) {
            generateCliIpcInfoForEffectiveUser(authTokenDir);
            return;
        }
        String posixGroups = Coerce.toString(authorizedPosixGroups);
        if (posixGroups == null || posixGroups.length() == 0) {
            generateCliIpcInfoForEffectiveUser(authTokenDir);
            return;
        }
        for (String posixGroup : posixGroups.split(",")) {
            UserPlatform.BasicAttributes group;
            try {
                group = getGroup(posixGroup);
            } catch (NumberFormatException | IOException e) {
                logger.atError().kv("posixGroup", posixGroup).log("Failed to get group ID", e);
                continue;
            }
            generateCliIpcInfoForPosixGroup(group, authTokenDir);
        }
    }

    @SuppressFBWarnings(value = {"RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", "RV_RETURN_VALUE_IGNORED"},
            justification = "File is created in the same method")
    private synchronized void generateCliIpcInfoForEffectiveUser(Path directory)
            throws UnauthenticatedException, IOException, InterruptedException {
        String defaultClientId =
                USER_CLIENT_ID_PREFIX + Platform.getInstance().lookupCurrentUser().getPrincipalIdentifier();
        Path ipcInfoFile = generateCliIpcInfoForClient(defaultClientId, directory);
        if (ipcInfoFile == null) {
            return;
        }
        Platform.getInstance().setPermissions(DEFAULT_FILE_PERMISSION, ipcInfoFile);
    }

    private synchronized void generateCliIpcInfoForPosixGroup(UserPlatform.BasicAttributes group, Path directory)
            throws UnauthenticatedException, IOException {
        Path ipcInfoFile = generateCliIpcInfoForClient(getClientIdForGroup(group.getPrincipalIdentifier()), directory);
        if (ipcInfoFile == null) {
            return;
        }


        FileSystemPermission filePermission = FileSystemPermission.builder()
                .ownerGroup(group.getPrincipalName()).ownerRead(true).ownerWrite(true).groupRead(true).build();
        try {
            Platform.getInstance().setPermissions(filePermission, ipcInfoFile);
        } catch (IOException e) {
            logger.atError().kv("file", ipcInfoFile).kv("permission", filePermission)
                    .kv("groupOwner", group.getPrincipalName()).log("Failed to set up posix file permissions and"
                    + " group owner.  Admin may have to manually update the file permission so that CLI authentication "
                    + "works as intended", e);
        }
    }

    private synchronized Path generateCliIpcInfoForClient(String clientId, Path directory)
            throws UnauthenticatedException, IOException {
        if (clientIdToAuthToken.containsKey(clientId)) {
            // Duplicate user input. No need to override auth token.
            return null;
        }

        String cliAuthToken = authenticationHandler.registerAuthenticationTokenForExternalClient(
                Coerce.toString(getPrivateConfig().find(SERVICE_UNIQUE_ID_KEY)), getAuthClientIdentifier(clientId));

        clientIdToAuthToken.put(clientId, cliAuthToken);

        Map<String, String> ipcInfo = new HashMap<>();
        ipcInfo.put(CLI_AUTH_TOKEN, cliAuthToken);
        // GG_NEEDS_REVIEW: TODO: Remove when UAT move to the new IPC
        ipcInfo.put(SOCKET_URL, Coerce.toString(
                config.getRoot().find(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME)));
        ipcInfo.put(DOMAIN_SOCKET_PATH, Coerce.toString(
                config.getRoot().find(SETENV_CONFIG_NAMESPACE, NUCLEUS_DOMAIN_SOCKET_FILEPATH)));

        Path filePath = directory.resolve(clientId);
        Files.write(filePath, OBJECT_MAPPER.writeValueAsString(ipcInfo)
                .getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ipcInfo.clear();
        return filePath;
    }

    private String getAuthClientIdentifier(String clientId) {
        return String.format(GREENGRASS_CLI_CLIENT_ID_FMT, clientId);
    }

    @SuppressFBWarnings(value = {"RV_RETURN_VALUE_IGNORED_BAD_PRACTICE"},
            justification = "File to be deleted should exist")
    private synchronized void revokeOutdatedAuthTokens(Path authTokenDir) throws UnauthenticatedException {
        for (Map.Entry<String, String> entry : clientIdToAuthToken.entrySet()) {
            authenticationHandler.revokeAuthenticationTokenForExternalClient(
                    Coerce.toString(getPrivateConfig().find(SERVICE_UNIQUE_ID_KEY)), entry.getValue());
        }
        clientIdToAuthToken.clear();
        File[] allContents = authTokenDir.toFile().listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                try {
                    Files.delete(file.toPath());
                } catch (IOException e) {
                    logger.atWarn().log("Unable to delete auth file " + file, e);
                }
            }
        }
        logger.atInfo().log("Auth tokens have been revoked");
    }

    @Data
    public static class LocalDeploymentDetails {
        String deploymentId;
        DeploymentStatus status;
    }

    @SuppressWarnings("PMD.EmptyIfStmt")
    protected Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        cliEventStreamAgent.persistLocalDeployment(config, deploymentDetails);
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
            // GG_NEEDS_REVIEW: TODO: add version compatibility check
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
