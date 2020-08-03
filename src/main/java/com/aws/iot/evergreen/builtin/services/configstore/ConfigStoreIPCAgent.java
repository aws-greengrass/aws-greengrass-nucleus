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
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ReportConfigurationValidityRequest;
import com.aws.iot.evergreen.ipc.services.configstore.ReportConfigurationValidityResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToValidateConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ValidateConfigurationUpdateEvent;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.kernel.EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

/**
 * Class to handle business logic for all ConfigStore requests over IPC.
 */
public class ConfigStoreIPCAgent implements InjectionActions {
    // Map from connection --> Function to call when service config changes
    private static final Map<String, CopyOnWriteArraySet<ConnectionContext>> configUpdateSubscribersByService =
            new ConcurrentHashMap<>();
    private static final Map<ConnectionContext, Consumer<String>> configUpdateListeners =
            new ConcurrentHashMap<>();
    private static final Map<ConnectionContext, Consumer<Map<String, Object>>> configValidationListeners =
            new ConcurrentHashMap<>();
    private static final int TIMEOUT_SECONDS = 30;
    private static final Logger log = LogManager.getLogger(ConfigStoreIPCAgent.class);

    @Inject
    private Kernel kernel;

    @Inject
    private ExecutorService executor;

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

        // Ensure that the node which changed was part of component configuration/ runtime configuration
        int configIndex = 2;
        if (!PARAMETERS_CONFIG_KEY.equals(nodePath[configIndex])) {
            return;
        } else if (nodePath[configIndex].equals(RUNTIME_STORE_NAMESPACE_TOPIC)) {
            // Do not send update event for runtime config changes to the owner service
            // A service updates its own runtime config so it does not need to be updated for it
            subscribers.stream().filter(ctx -> !ctx.getServiceName().equals(serviceName))
                    .map(ctx -> configUpdateListeners.get(ctx)).forEach(c -> c.accept(nodePath[configIndex + 1]));
        } else {
            return;
        }
    };

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
            return sendStoreUpdateToListener(context, componentName);
        });

        configUpdateSubscribersByService.putIfAbsent(componentName, new CopyOnWriteArraySet<>());
        configUpdateSubscribersByService.get(componentName).add(context);
        context.onDisconnect(() -> {
            configUpdateSubscribersByService.entrySet().forEach(e -> {
                if (e.getValue().contains(context)) {
                    e.getValue().remove(context);
                }
            });
        });

        return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.Success, null);
    }

    private Consumer<String> sendStoreUpdateToListener(ConnectionContext context, String componentName) {
        return (changedKey) -> {
            ConfigurationUpdateEvent valueChangedEvent =
                    ConfigurationUpdateEvent.builder().componentName(componentName).changedKey(changedKey).build();
            log.atDebug().log("Sending updated config key {} to {}", changedKey, context);

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

        // TODO : Ongoing discussion about handling runtime vs deployment managed component config
        //  in IPC API's. This logic should be updated based on the outcome, for now, runtime config
        //  takes precedence over dynamic configuration in the case of conflicts
        Topics runtimeTopics = serviceTopic.findInteriorChild(RUNTIME_STORE_NAMESPACE_TOPIC);
        Topics parametersTopics = serviceTopic.findInteriorChild(PARAMETERS_CONFIG_KEY);
        if (runtimeTopics == null && parametersTopics == null) {
            return response.responseStatus(ConfigStoreResponseStatus.NoConfig)
                    .errorMessage("Service has no runtime or dynamic config").build();
        }

        Node node = null;
        if (runtimeTopics != null) {
            node = runtimeTopics.getChild(request.getKey());
        }
        if (node == null && parametersTopics != null) {
            node = parametersTopics.getChild(request.getKey());
        }
        if (node == null) {
            return response.responseStatus(ConfigStoreResponseStatus.NotFound).errorMessage("Key not found").build();
        }

        response.responseStatus(ConfigStoreResponseStatus.Success);
        if (node instanceof Topic) {
            response.value(((Topic) node).getOnce());
        } else if (node instanceof Topics) {
            response.value(((Topics) node).toPOJO());
        } else {
            response.responseStatus(ConfigStoreResponseStatus.InternalError).errorMessage("Node has an unknown type");
            log.atError().log("Somehow Node has an unknown type {}", node.getClass());
        }

        return response.build();
    }

    /**
     * Update specified key in the service's runtime config.
     *
     * @param request update config request
     * @param context client context
     * @return response data
     */
    public UpdateConfigurationResponse updateConfig(UpdateConfigurationRequest request, ConnectionContext context) {
        log.atDebug().kv("context", context).log("Config IPC config update request");
        Topics serviceTopic = kernel.findServiceTopic(context.getServiceName());
        UpdateConfigurationResponse.UpdateConfigurationResponseBuilder response = UpdateConfigurationResponse.builder();
        if (serviceTopic == null) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest).errorMessage("Service not found")
                    .build();
        }

        serviceTopic.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, request.getKey())
                .withNewerValue(request.getTimestamp(), request.getValue());

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
    public ReportConfigurationValidityResponse reportConfigValidity(ReportConfigurationValidityRequest request,
                                                                    ConnectionContext context) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        log.atDebug().kv("context", context).log("Config IPC report config validation request");
        Topics serviceTopic = kernel.findServiceTopic(context.getServiceName());
        ReportConfigurationValidityResponse.ReportConfigurationValidityResponseBuilder response =
                ReportConfigurationValidityResponse.builder();
        if (serviceTopic == null) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest).errorMessage("Service not found")
                    .build();
        }

        log.atInfo().addKeyValue("status", request.getStatus()).addKeyValue("message", request.getMessage()).log();
        // TODO : Invoke caller's (Deployment service) handler to use the reported status and
        //  message

        response.responseStatus(ConfigStoreResponseStatus.Success);

        return response.build();
    }

    /**
     * Trigger a validate event to service/component, typically used during deployments.
     *
     * @param componentName service/component to send validate event to
     * @param configuration new component configuration to validate
     * @return true if the service has registered a validator, false if not
     */
    public boolean validateConfiguration(String componentName, Map<String, Object> configuration) {
        // TODO : Accept a handler from the requester and track it so that when the component
        //  reports the validation result, the handler can be invoked
        for (Map.Entry<ConnectionContext, Consumer<Map<String, Object>>> e : configValidationListeners.entrySet()) {
            if (e.getKey().getServiceName().equals(componentName)) {
                e.getValue().accept(configuration);
                return true;
            }
        }
        return false;
    }
}
