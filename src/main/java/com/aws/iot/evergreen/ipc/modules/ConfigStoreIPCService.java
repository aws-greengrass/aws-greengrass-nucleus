package com.aws.iot.evergreen.ipc.modules;


import com.aws.iot.evergreen.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreClientOpCodes;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreGenericResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SendConfigurationValidityReportRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateRequest;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

//TODO: see if this needs to be a GGService
@ImplementsService(name = "configstoreipc", autostart = true)
public class ConfigStoreIPCService extends EvergreenService {
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();

    @Inject
    private IPCRouter router;

    @Inject
    private ConfigStoreIPCAgent agent;

    public ConfigStoreIPCService(Topics c) {
        super(c);
    }

    @Override
    public void postInject() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.CONFIG_STORE;
        super.postInject();
        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo().setEventType("ipc-register-request-handler").addKeyValue("destination", destination.name())
                    .log();
        } catch (IPCException e) {
            logger.atError().setEventType("ipc-register-request-handler-error").setCause(e)
                    .addKeyValue("destination", destination.name())
                    .log("Failed to register service callback to destination");
        }
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
            //TODO: add version compatibility check
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
                case REPORT_CONFIG_VALIDITY:
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
                        new ConfigStoreGenericResponse(ConfigStoreResponseStatus.ServiceError, e.getMessage());
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
}
