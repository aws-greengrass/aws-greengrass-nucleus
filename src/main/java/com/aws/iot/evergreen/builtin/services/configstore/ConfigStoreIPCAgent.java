/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.builtin.services.configstore;

import com.aws.iot.evergreen.config.ChildChanged;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.Watcher;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.ServiceEventHelper;
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
import com.aws.iot.evergreen.util.Utils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

/**
 * Class to handle business logic for all ConfigStore requests over IPC.
 */
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class ConfigStoreIPCAgent {
    private static final Logger log = LogManager.getLogger(ConfigStoreIPCAgent.class);
    private static final String CONFIGURATION_KEY_PATH_DELIMITER = ".";

    // Map from connection --> Function to call for triggering config validation events
    private final Map<ConnectionContext, Consumer<Map<String, Object>>> configValidationListeners =
            new ConcurrentHashMap<>();
    // Map of component --> future to complete with validation status received from service in response to validate
    // event
    private final Map<String, CompletableFuture<ConfigurationValidityReport>> configValidationReportFutures =
            new ConcurrentHashMap<>();

    @Inject
    private Kernel kernel;

    @Inject
    private ServiceEventHelper serviceEventHelper;

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

        Topics serviceTopics = kernel.findServiceTopic(componentName);
        if (serviceTopics == null) {
            return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.ResourceNotFoundError,
                    "Requested component does not exist");
        }

        Topics configurationTopics = serviceTopics.lookupTopics(PARAMETERS_CONFIG_KEY);
        if (configurationTopics == null) {
            return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.ResourceNotFoundError,
                    "Requested component does not have any configuration");
        }

        Node subscribeTo = getNodeToSubscribeTo(configurationTopics, request.getKeyName());
        if (subscribeTo == null) {
            return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.ResourceNotFoundError,
                    "Requested configuration key does not exist");
        }

        Optional<Watcher> watcher = registerWatcher(subscribeTo, context, componentName);
        if (!watcher.isPresent()) {
            return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.InternalError,
                    "Could not register update subscriber");
        }

        context.onDisconnect(() -> subscribeTo.remove(watcher.get()));

        return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.Success, null);
    }

    private Node getNodeToSubscribeTo(Topics configurationTopics, String keyName) {
        Node subscribeTo = configurationTopics;
        if (!Utils.isEmpty(keyName)) {
            String[] keyPath = parseKeyPath(keyName);
            subscribeTo = configurationTopics.findNode(keyPath);
        }
        return subscribeTo;
    }

    // TODO : We shouldn't be splitting or joining paths based on the '.' delimiter(or any char as delimiter)
    //  since it will not work when keys have names with '.' in them, instead, we should explore if we can
    //  use string arrays for explicitly denoting key paths in Get/Update/Subscribe API models
    //  keeping this as a TODO while the decision is made
    private String[] parseKeyPath(String key) {
        if (key.contains(CONFIGURATION_KEY_PATH_DELIMITER)) {
            return key.split(CONFIGURATION_KEY_PATH_DELIMITER);
        }
        return new String[] {key};
    }

    private Optional<Watcher> registerWatcher(Node subscribeTo, ConnectionContext context, String componentName) {
        if (subscribeTo instanceof Topics) {
            ChildChanged childChanged =
                    (whatHappened, node) -> handleConfigNodeUpdate(whatHappened, node, context, componentName);

            ((Topics) subscribeTo).subscribe(childChanged);
            return Optional.of(childChanged);

        } else if (subscribeTo instanceof Topic) {
            Subscriber subscriber =
                    (whatHappened, topic) -> handleConfigNodeUpdate(whatHappened, topic, context, componentName);

            ((Topic) subscribeTo).subscribe(subscriber);
            return Optional.of(subscriber);
        }
        return Optional.empty();
    }

    private void handleConfigNodeUpdate(WhatHappened whatHappened, Node changedNode, ConnectionContext context,
                                        String componentName) {
        // Blocks from sending an event on subscription
        if (changedNode == null || WhatHappened.initialized.equals(whatHappened)) {
            return;
        }
        // The path sent in config update event should be the path for the changed node within the component
        // 'configuration' namespace such that it can be used as it is to make a subsequent get call by the client.
        // e.g. if the path for changed node is services.<service_name>.configuration.key_1.nested_key_1
        // then the path in update event should be key_1.nested_key_1
        int configurationTopicsIndex =
                kernel.findServiceTopic(componentName).lookupTopics(PARAMETERS_CONFIG_KEY).path().length - 1;
        String[] keyPath =
                Arrays.copyOfRange(changedNode.path(), configurationTopicsIndex + 1,
                        changedNode.path().length);

        sendConfigUpdateToListener(context, componentName)
                .accept(String.join(CONFIGURATION_KEY_PATH_DELIMITER, keyPath));
    }

    private Consumer<String> sendConfigUpdateToListener(ConnectionContext context, String componentName) {
        return (changedKey) -> {
            ConfigurationUpdateEvent valueChangedEvent =
                    ConfigurationUpdateEvent.builder().componentName(componentName).changedKey(changedKey).build();
            log.atDebug().log("Sending component {}'s updated config key {} to {}", componentName, changedKey, context);

            serviceEventHelper.sendServiceEvent(context, valueChangedEvent, BuiltInServiceDestinationCode.CONFIG_STORE,
                    ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal());
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
        Topics serviceTopics = kernel.findServiceTopic(serviceName);
        GetConfigurationResponse.GetConfigurationResponseBuilder response = GetConfigurationResponse.builder();

        if (serviceTopics == null) {
            return response.responseStatus(ConfigStoreResponseStatus.ResourceNotFoundError)
                    .errorMessage("Service not found").build();
        }

        Topics configTopics = serviceTopics.findInteriorChild(PARAMETERS_CONFIG_KEY);
        if (configTopics == null) {
            return response.responseStatus(ConfigStoreResponseStatus.NoConfig)
                    .errorMessage("Service has no dynamic config").build();
        }

        Node node;
        if (request.getKey() == null) {
            // Request is for reading all configuration
            node = configTopics;
        } else {
            String[] keyPath = parseKeyPath(request.getKey());
            node = configTopics.findNode(keyPath);
            if (node == null) {
                return response.responseStatus(ConfigStoreResponseStatus.ResourceNotFoundError)
                        .errorMessage("Key not found").build();
            }
        }

        response.responseStatus(ConfigStoreResponseStatus.Success);
        response.componentName(serviceName);
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
     * Update specified key in the service's configuration.
     *
     * @param request update config request
     * @param context client context
     * @return response data
     */
    public UpdateConfigurationResponse updateConfig(UpdateConfigurationRequest request, ConnectionContext context) {
        log.atDebug().kv("context", context).log("Config IPC config update request");
        UpdateConfigurationResponse.UpdateConfigurationResponseBuilder response = UpdateConfigurationResponse.builder();

        if (request.getKey() == null) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest).errorMessage("Key is required")
                    .build();
        }

        if (request.getComponentName() != null && !context.getServiceName().equals(request.getComponentName())) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest)
                    .errorMessage("Cross component updates are not allowed").build();
        }

        Topics serviceTopic = kernel.findServiceTopic(context.getServiceName());
        if (serviceTopic == null) {
            return response.responseStatus(ConfigStoreResponseStatus.InternalError)
                    .errorMessage("Service config not found").build();
        }
        Topics configTopic = serviceTopic.lookupTopics(PARAMETERS_CONFIG_KEY);
        String[] keyPath = parseKeyPath(request.getKey());
        configTopic.lookup(keyPath).withNewerValue(request.getTimestamp(), request.getNewValue());

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

            serviceEventHelper.sendServiceEvent(context, validationEvent, BuiltInServiceDestinationCode.CONFIG_STORE,
                    ConfigStoreServiceOpCodes.VALIDATION_EVENT.ordinal());
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
        // TODO : Will handling a collection of components to abstract validation for the whole deployment
        //  be better?
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
