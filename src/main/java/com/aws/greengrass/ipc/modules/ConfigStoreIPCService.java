/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCEventStreamAgent;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.IPCRouter;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader.Message;
import com.aws.greengrass.ipc.exceptions.IPCException;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreClientOpCodes;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreGenericResponse;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.greengrass.ipc.services.configstore.GetConfigurationRequest;
import com.aws.greengrass.ipc.services.configstore.SendConfigurationValidityReportRequest;
import com.aws.greengrass.ipc.services.configstore.SubscribeToConfigurationUpdateRequest;
import com.aws.greengrass.ipc.services.configstore.UpdateConfigurationRequest;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

public class ConfigStoreIPCService implements Startable {
    private static final Logger logger = LogManager.getLogger(ConfigStoreIPCService.class);
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();

    private final IPCRouter router;
    private final ConfigStoreIPCAgent agent;
    private final ConfigStoreIPCEventStreamAgent eventStreamAgent;
    private final GreengrassCoreIPCService greengrassCoreIPCService;

    /**
     * Constructor.
     *
     * @param router ipc router
     * @param agent config store ipc agent
     * @param eventStreamAgent {@link ConfigStoreIPCEventStreamAgent}
     * @param greengrassCoreIPCService {@link GreengrassCoreIPCService}
     */
    @Inject
    public ConfigStoreIPCService(IPCRouter router, ConfigStoreIPCAgent agent,
                                 ConfigStoreIPCEventStreamAgent eventStreamAgent,
                                 GreengrassCoreIPCService greengrassCoreIPCService) {
        this.router = router;
        this.agent = agent;
        this.eventStreamAgent = eventStreamAgent;
        this.greengrassCoreIPCService = greengrassCoreIPCService;
    }

    /**
     * Handle all requests from the client.
     *
     * @param message the incoming request
     * @param context caller request context
     * @return future containing our response
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Future<Message> handleMessage(Message message, ConnectionContext context) {
        CompletableFuture<Message> fut = new CompletableFuture<>();

        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        try {
            // GG_NEEDS_REVIEW: TODO: add version compatibility check
            ConfigStoreClientOpCodes opCode = ConfigStoreClientOpCodes.values()[applicationMessage.getOpCode()];
            ConfigStoreGenericResponse configStoreGenericResponse = new ConfigStoreGenericResponse();
            switch (opCode) {
                case SUBSCRIBE_TO_ALL_CONFIG_UPDATES:
                    SubscribeToConfigurationUpdateRequest subscribeToConfigUpdateRequest = CBOR_MAPPER
                            .readValue(applicationMessage.getPayload(), SubscribeToConfigurationUpdateRequest.class);
                    configStoreGenericResponse = agent.subscribeToConfigUpdate(subscribeToConfigUpdateRequest, context);
                    break;
                case GET_CONFIG:
                    GetConfigurationRequest getConfigRequest =
                            CBOR_MAPPER.readValue(applicationMessage.getPayload(), GetConfigurationRequest.class);
                    configStoreGenericResponse = agent.getConfig(getConfigRequest, context);
                    break;
                case UPDATE_CONFIG:
                    UpdateConfigurationRequest updateConfigRequest =
                            CBOR_MAPPER.readValue(applicationMessage.getPayload(), UpdateConfigurationRequest.class);
                    configStoreGenericResponse = agent.updateConfig(updateConfigRequest, context);
                    break;
                case SUBSCRIBE_TO_CONFIG_VALIDATION:
                    configStoreGenericResponse = agent.subscribeToConfigValidation(context);
                    break;
                case SEND_CONFIG_VALIDATION_REPORT:
                    SendConfigurationValidityReportRequest reportConfigValidityRequest = CBOR_MAPPER
                            .readValue(applicationMessage.getPayload(), SendConfigurationValidityReportRequest.class);
                    configStoreGenericResponse = agent.handleConfigValidityReport(reportConfigValidityRequest, context);
                    break;
                default:
                    configStoreGenericResponse.setStatus(ConfigStoreResponseStatus.InvalidRequest);
                    configStoreGenericResponse.setErrorMessage("Unknown request type " + opCode.toString());
                    break;
            }

            ApplicationMessage responseMessage = ApplicationMessage.builder().version(applicationMessage.getVersion())
                    .payload(CBOR_MAPPER.writeValueAsBytes(configStoreGenericResponse)).build();
            fut.complete(new Message(responseMessage.toByteArray()));
        } catch (Throwable e) {
            logger.atError().setEventType("configstore-ipc-error").setCause(e).log("Failed to handle message");
            try {
                ConfigStoreGenericResponse response =
                        new ConfigStoreGenericResponse(ConfigStoreResponseStatus.InternalError, e.getMessage());
                ApplicationMessage responseMessage =
                        ApplicationMessage.builder().version(applicationMessage.getVersion())
                                .payload(CBOR_MAPPER.writeValueAsBytes(response)).build();
                fut.complete(new Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError("configstore-ipc-error", ex).log("Failed to send error response");
            }
        }
        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }
        return fut;
    }

    @Override
    public void startup() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.CONFIG_STORE;
        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo().setEventType("ipc-register-request-handler").addKeyValue("destination", destination.name())
                    .log();
        } catch (IPCException e) {
            logger.atError().setEventType("ipc-register-request-handler-error").setCause(e)
                    .addKeyValue("destination", destination.name())
                    .log("Failed to register service callback to destination");
        }

        greengrassCoreIPCService.setUpdateConfigurationHandler(
                (context) -> eventStreamAgent.getUpdateConfigurationHandler(context));
        greengrassCoreIPCService.setSendConfigurationValidityReportHandler(
                (context) -> eventStreamAgent.getSendConfigurationValidityReportHandler(context));
        greengrassCoreIPCService.setGetConfigurationHandler(
                (context) -> eventStreamAgent.getGetConfigurationHandler(context));
        greengrassCoreIPCService.setSubscribeToConfigurationUpdateHandler(
                (context) -> eventStreamAgent.getConfigurationUpdateHandler(context));
        greengrassCoreIPCService.setSubscribeToValidateConfigurationUpdatesHandler(
                (context) -> eventStreamAgent.getValidateConfigurationUpdatesHandler(context));

    }
}
