package com.aws.iot.evergreen.builtin.services.configstore;

import com.aws.iot.evergreen.config.ChildChanged;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreImpl;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreServiceOpCodes;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationUpdateEvent;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SendConfigurationValidityReportRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SendConfigurationValidityReportResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToValidateConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ValidateConfigurationUpdateEvent;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.Builder;
import lombok.Getter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

/**
 * Class to handle business logic for all ConfigStore requests over IPC.
 */
public class ConfigStoreIPCAgent implements InjectionActions {
    // Map from component --> config update event subscribers
    private static final Map<String, CopyOnWriteArraySet<ConnectionContext>> configUpdateSubscribersByService =
            new ConcurrentHashMap<>();
    // Map from connection --> Function to call for triggering config change events
    private static final Map<ConnectionContext, BiConsumer<String, String>> configUpdateListeners =
            new ConcurrentHashMap<>();
    // Map from connection --> Function to call for triggering config validation events
    private static final Map<ConnectionContext, Consumer<Map<String, Object>>> configValidationListeners =
            new ConcurrentHashMap<>();
    // Map of component --> future to complete with validation status received from service in response to validate
    // event
    private static final Map<String, CompletableFuture<ConfigurationValidityReport>> configValidationReportFutures =
            new ConcurrentHashMap<>();
    private static final int TIMEOUT_SECONDS = 30;
    private static final Logger log = LogManager.getLogger(ConfigStoreIPCAgent.class);
    private final ChildChanged onConfigChange = (whatHappened, node) -> {
        if (node == null) {
            return;
        }
        String serviceName = Kernel.findServiceForNode(node);
        // Ensure a the node that changed belongs to a service
        if (serviceName == null) {
            return;
        }

        String[] nodePath = node.path();
        // The path should have at least 4 items: services, serviceName, parameters/runtime, <someKey>
        if (nodePath.length < 4) {
            return;
        }

        Set<ConnectionContext> subscribers = configUpdateSubscribersByService.get(serviceName);
        // Do nothing if no one has subscribed
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        // Ensure that the node which changed was part of component configuration
        int configIndex = 2;
        if (!PARAMETERS_CONFIG_KEY.equals(nodePath[configIndex])) {
            return;
        }
        subscribers.stream().map(ctx -> configUpdateListeners.get(ctx))
                .forEach(c -> c.accept(serviceName, nodePath[configIndex + 1]));
    };
    @Inject
    private Kernel kernel;
    @Inject
    private ExecutorService executor;

    @Override
    public void postInject() {
        kernel.getConfig().getRoot().subscribe(onConfigChange);
    }

    /**
     * Handle the subscription request from the user. Immediately sends the current state to the client.
     *
     * @param request request for component update subscription
     * @param context connection context
     * @return response code Success if all went well
     */
    public SubscribeToConfigurationUpdateResponse subscribeToConfigUpdate(SubscribeToConfigurationUpdateRequest request,
                                                                          ConnectionContext context) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        String componentName =
                request.getComponentName() == null ? context.getServiceName() : request.getComponentName();

        configUpdateListeners.computeIfAbsent(context, (key) -> {
            context.onDisconnect(() -> configUpdateListeners.remove(context));
            return sendStoreUpdateToListener(context);
        });

        configUpdateSubscribersByService.putIfAbsent(componentName, new CopyOnWriteArraySet<>());
        configUpdateSubscribersByService.get(componentName).add(context);
        context.onDisconnect(
                () -> configUpdateSubscribersByService.entrySet().forEach(e -> e.getValue().remove(context)));

        return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.Success, null);
    }

    private BiConsumer<String, String> sendStoreUpdateToListener(ConnectionContext context) {
        return (componentName, changedKey) -> {
            ConfigurationUpdateEvent valueChangedEvent =
                    ConfigurationUpdateEvent.builder().componentName(componentName).changedKey(changedKey).build();
            log.atDebug().log("Sending component {}'s updated config key {} to {}", componentName, changedKey, context);

            try {
                ApplicationMessage applicationMessage =
                        ApplicationMessage.builder().version(ConfigStoreImpl.API_VERSION)
                                .opCode(ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal())
                                .payload(IPCUtil.encode(valueChangedEvent)).build();
                // TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                Future<FrameReader.Message> fut =
                        context.serverPush(BuiltInServiceDestinationCode.CONFIG_STORE.getValue(),
                                new FrameReader.Message(applicationMessage.toByteArray()));

                // call the blocking "get" in a separate thread so we don't block the publish queue
                executor.execute(() -> {
                    try {
                        fut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        // TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        // Log
                        log.atError("error-sending-configstore-update").kv("context", context)
                                .log("Error sending config store update to client", e);
                    }
                });

            } catch (IOException e) {
                // Log
                log.atError("error-sending-configstore-update").kv("context", context)
                        .log("Error sending config store update to client", e);
            }
        };
    }

    /**
     * Read specified key from the service's dynamic config.
     *
     * @param request request
     * @param context client context
     * @return response data
     */
    public GetConfigurationResponse getConfig(GetConfigurationRequest request, ConnectionContext context) {
        log.atDebug().kv("context", context).log("Config IPC get config request");
        String serviceName = request.getComponentName() == null ? context.getServiceName() : request.getComponentName();
        Topics serviceTopic = kernel.findServiceTopic(serviceName);
        GetConfigurationResponse.GetConfigurationResponseBuilder response = GetConfigurationResponse.builder();
        if (serviceTopic == null) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest).errorMessage("Service not found")
                    .build();
        }

        Topics configTopics = serviceTopic.findInteriorChild(PARAMETERS_CONFIG_KEY);
        if (configTopics == null) {
            return response.responseStatus(ConfigStoreResponseStatus.NoConfig)
                    .errorMessage("Service has no dynamic config").build();
        }

        Node node = configTopics.getChild(request.getKey());
        if (node == null) {
            return response.responseStatus(ConfigStoreResponseStatus.ResourceNotFoundError)
                    .errorMessage("Key not found").build();
        }

        response.responseStatus(ConfigStoreResponseStatus.Success);
        if (node instanceof Topic) {
            response.value(((Topic) node).getOnce());
        } else if (node instanceof Topics) {
            response.value(((Topics) node).toPOJO());
        } else {
            response.responseStatus(ConfigStoreResponseStatus.ServiceError).errorMessage("Node has an unknown type");
            log.atError().log("Somehow Node has an unknown type {}", node.getClass());
        }

        return response.build();
    }

    /**
     * Update specified key in the service's configuration.
     *
     * @param request update config request
     * @param context client context
     * @return response data
     */
    public UpdateConfigurationResponse updateConfig(UpdateConfigurationRequest request, ConnectionContext context) {
        log.atDebug().kv("context", context).log("Config IPC config update request");
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        UpdateConfigurationResponse.UpdateConfigurationResponseBuilder response = UpdateConfigurationResponse.builder();

        if (!context.getServiceName().equals(request.getComponentName())) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest)
                    .errorMessage("Cross component updates are not allowed").build();
        }

        Topics serviceTopic = kernel.findServiceTopic(context.getServiceName());
        if (serviceTopic == null) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest).errorMessage("Service not found")
                    .build();
        }

        serviceTopic.lookup(PARAMETERS_CONFIG_KEY, request.getKey())
                .withNewerValue(request.getTimestamp(), request.getNewValue());

        response.responseStatus(ConfigStoreResponseStatus.Success);

        return response.build();
    }

    /**
     * Handle subscription request from the user for validating config changes before deployments.
     *
     * @param context connection context
     * @return response code Success if all went well
     */
    public SubscribeToValidateConfigurationResponse subscribeToConfigValidation(ConnectionContext context) {
        log.atDebug().kv("context", context).log("Config IPC subscribe to config validation request");
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        configValidationListeners.computeIfAbsent(context, (key) -> {
            context.onDisconnect(() -> configValidationListeners.remove(context));
            return sendConfigValidationEvent(context);
        });

        return SubscribeToValidateConfigurationResponse.builder().responseStatus(ConfigStoreResponseStatus.Success)
                .build();
    }

    private Consumer<Map<String, Object>> sendConfigValidationEvent(ConnectionContext context) {
        return (configuration) -> {
            ValidateConfigurationUpdateEvent validationEvent =
                    ValidateConfigurationUpdateEvent.builder().configuration(configuration).build();
            log.atDebug().log("Requesting validation for component config {}", configuration, context);

            try {
                ApplicationMessage applicationMessage =
                        ApplicationMessage.builder().version(ConfigStoreImpl.API_VERSION)
                                .opCode(ConfigStoreServiceOpCodes.VALIDATION_EVENT.ordinal())
                                .payload(IPCUtil.encode(validationEvent)).build();
                // TODO: Add timeout and retry to make sure the client got the request. https://sim.amazon.com/issues/P32541289
                Future<FrameReader.Message> fut =
                        context.serverPush(BuiltInServiceDestinationCode.CONFIG_STORE.getValue(),
                                new FrameReader.Message(applicationMessage.toByteArray()));

                // call the blocking "get" in a separate thread so we don't block the publish queue
                executor.execute(() -> {
                    try {
                        fut.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        // TODO: Check the response message and make sure it was successful. https://sim.amazon.com/issues/P32541289
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        // Log
                        log.atError("error-requesting-config-validation").kv("context", context)
                                .log("Error sending config validation event to client", e);
                    }
                });

            } catch (IOException e) {
                // Log
                log.atError("error-requesting-config-validation").kv("context", context)
                        .log("Error sending config validation event to client", e);
            }
        };
    }

    /**
     * Handle user service's response to config validation request.
     *
     * @param request request to report validation status for config
     * @param context client context
     * @return response data
     */
    public SendConfigurationValidityReportResponse handleConfigValidityReport(
            SendConfigurationValidityReportRequest request, ConnectionContext context) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        log.atDebug().kv("context", context).log("Config IPC report config validation request");
        SendConfigurationValidityReportResponse.SendConfigurationValidityReportResponseBuilder response =
                SendConfigurationValidityReportResponse.builder();
        // TODO : Edge case - With the current API model, there is no way to associate a validation report from client
        //  with the event sent from server, meaning if event 1 from server was abandoned due to timeout, then event
        //  2 was triggered, then report in response to event 1 arrives, server won't detect this.
        if (!configValidationReportFutures.containsKey(context.getServiceName())) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest)
                    .errorMessage("Validation request either timed out or was never made").build();
        }

        CompletableFuture<ConfigurationValidityReport> reportFuture =
                configValidationReportFutures.get(context.getServiceName());
        if (!reportFuture.isCancelled()) {
            reportFuture.complete(
                    ConfigurationValidityReport.builder().status(request.getStatus()).message(request.getMessage())
                            .build());
        }
        configValidationReportFutures.remove(context.getServiceName());

        response.responseStatus(ConfigStoreResponseStatus.Success);

        return response.build();
    }

    /**
     * Trigger a validate event to service/component, typically used during deployments.
     *
     * @param componentName service/component to send validate event to
     * @param configuration new component configuration to validate
     * @param reportFuture  future to track validation report in response to the event
     * @return true if the service has registered a validator, false if not
     * @throws UnsupportedOperationException throws when triggering requested validation event is not allowed
     */
    public boolean validateConfiguration(String componentName, Map<String, Object> configuration,
                                         CompletableFuture<ConfigurationValidityReport> reportFuture)
            throws UnsupportedOperationException {
        // TODO : Consider handling a collection of components to abstract validation for the whole deployment
        if (configValidationReportFutures.containsKey(componentName)) {
            throw new UnsupportedOperationException(
                    "A validation request to this component is already waiting for response");
        }

        for (Map.Entry<ConnectionContext, Consumer<Map<String, Object>>> e : configValidationListeners.entrySet()) {
            if (e.getKey().getServiceName().equals(componentName)) {
                configValidationReportFutures.put(componentName, reportFuture);
                e.getValue().accept(configuration);
                return true;
            }
        }
        return false;
    }

    /**
     * Abandon tracking for report of configuration validation event. Can be used by the caller in the case of timeouts
     * or other errors.
     *
     * @param componentName component name to abandon validation for
     * @param reportFuture  tracking future for validation report to abandon
     * @return true if abandon request was successful
     */
    public boolean discardValidationReportTracker(String componentName,
                                                  CompletableFuture<ConfigurationValidityReport> reportFuture) {
        return configValidationReportFutures.remove(componentName, reportFuture);
    }

    // TODO: If it adds value, add this to the SendConfigurationValidityReportRequest in smithy model
    @Builder
    @Getter
    public static class ConfigurationValidityReport {
        private ConfigurationValidityStatus status;
        private String message;
    }
}
