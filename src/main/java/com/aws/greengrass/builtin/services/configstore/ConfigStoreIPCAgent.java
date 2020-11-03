/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.configstore;

import com.aws.greengrass.builtin.services.configstore.exceptions.ValidateEventRegistrationException;
import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UnsupportedInputTypeException;
import com.aws.greengrass.config.Watcher;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.ServiceEventHelper;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreImpl;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreServiceOpCodes;
import com.aws.greengrass.ipc.services.configstore.ConfigurationUpdateEvent;
import com.aws.greengrass.ipc.services.configstore.ConfigurationValidityReport;
import com.aws.greengrass.ipc.services.configstore.GetConfigurationRequest;
import com.aws.greengrass.ipc.services.configstore.GetConfigurationResponse;
import com.aws.greengrass.ipc.services.configstore.SendConfigurationValidityReportRequest;
import com.aws.greengrass.ipc.services.configstore.SendConfigurationValidityReportResponse;
import com.aws.greengrass.ipc.services.configstore.SubscribeToConfigurationUpdateRequest;
import com.aws.greengrass.ipc.services.configstore.SubscribeToConfigurationUpdateResponse;
import com.aws.greengrass.ipc.services.configstore.SubscribeToValidateConfigurationResponse;
import com.aws.greengrass.ipc.services.configstore.UpdateConfigurationRequest;
import com.aws.greengrass.ipc.services.configstore.UpdateConfigurationResponse;
import com.aws.greengrass.ipc.services.configstore.ValidateConfigurationUpdateEvent;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

/**
 * Class to handle business logic for all ConfigStore requests over IPC.
 */
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class ConfigStoreIPCAgent {
    private static final Logger log = LogManager.getLogger(ConfigStoreIPCAgent.class);
    private static final String KEY_NOT_FOUND_ERROR_MESSAGE = "Key not found";
    private static final String CONTEXT_LOGGING_KEY = "context";

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
     * Handle the subscription request from the user.
     *
     * @param request request for component update subscription
     * @param context connection context
     * @return response code Success if all went well
     */
    public SubscribeToConfigurationUpdateResponse subscribeToConfigUpdate(SubscribeToConfigurationUpdateRequest request,
                                                                          ConnectionContext context) {
        // TODO: [P32540011]: All IPC service requests need input validation
        String componentName =
                request.getComponentName() == null ? context.getServiceName() : request.getComponentName();

        Topics serviceTopics = kernel.findServiceTopic(componentName);
        if (serviceTopics == null) {
            return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.ResourceNotFoundError,
                    KEY_NOT_FOUND_ERROR_MESSAGE);
        }

        Topics configurationTopics = serviceTopics.lookupTopics(PARAMETERS_CONFIG_KEY);
        if (configurationTopics == null) {
            return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.ResourceNotFoundError,
                    KEY_NOT_FOUND_ERROR_MESSAGE);
        }

        Node subscribeTo = getNodeToSubscribeTo(configurationTopics, request.getKeyPath());
        if (subscribeTo == null) {
            return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.ResourceNotFoundError,
                    KEY_NOT_FOUND_ERROR_MESSAGE);
        }

        Optional<Watcher> watcher = registerWatcher(subscribeTo, context, componentName);
        if (!watcher.isPresent()) {
            return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.InternalError,
                    "Could not register update subscriber");
        }

        context.onDisconnect(() -> subscribeTo.remove(watcher.get()));

        return new SubscribeToConfigurationUpdateResponse(ConfigStoreResponseStatus.Success, null);
    }

    private Node getNodeToSubscribeTo(Topics configurationTopics, List<String> keyPath) {
        Node subscribeTo = configurationTopics;
        if (keyPath != null && !keyPath.isEmpty()) {
            subscribeTo = configurationTopics.findNode(keyPath.toArray(new String[0]));
        }
        return subscribeTo;
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
                Arrays.copyOfRange(changedNode.path(), configurationTopicsIndex + 1, changedNode.path().length);

        sendConfigUpdateToListener(context, componentName).accept(keyPath);
    }

    private Consumer<String[]> sendConfigUpdateToListener(ConnectionContext context, String componentName) {
        return (changedKeyPath) -> {
            ConfigurationUpdateEvent valueChangedEvent = ConfigurationUpdateEvent.builder().componentName(componentName)
                    .changedKeyPath(Arrays.asList(changedKeyPath)).build();
            log.atDebug()
                    .log("Sending component {}'s updated config key {} to {}", componentName, changedKeyPath, context);

            serviceEventHelper.sendServiceEvent(context, valueChangedEvent, BuiltInServiceDestinationCode.CONFIG_STORE,
                    ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), ConfigStoreImpl.API_VERSION);
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
        log.atDebug().kv(CONTEXT_LOGGING_KEY, context).log("Config IPC get config request");
        String serviceName = request.getComponentName() == null ? context.getServiceName() : request.getComponentName();
        Topics serviceTopics = kernel.findServiceTopic(serviceName);
        GetConfigurationResponse.GetConfigurationResponseBuilder response = GetConfigurationResponse.builder();
        if (serviceTopics == null) {
            return response.responseStatus(ConfigStoreResponseStatus.ResourceNotFoundError)
                    .errorMessage(KEY_NOT_FOUND_ERROR_MESSAGE).build();
        }

        try {
            kernel.getConfig().waitConfigUpdateComplete();
        } catch (InterruptedException e) {
            log.atError().setCause(e).log("Interrupted when waiting for config update complete");
            return GetConfigurationResponse.builder()
                    .responseStatus(ConfigStoreResponseStatus.InternalError)
                    .errorMessage(e.getMessage()).build();
        }

        Topics configTopics = serviceTopics.findInteriorChild(PARAMETERS_CONFIG_KEY);
        if (configTopics == null) {
            return response.responseStatus(ConfigStoreResponseStatus.ResourceNotFoundError)
                    .errorMessage(KEY_NOT_FOUND_ERROR_MESSAGE).build();
        }

        Node node;
        if (Utils.isEmpty(request.getKeyPath())) {
            // Request is for reading all configuration
            node = configTopics;
        } else {
            String[] keyPath = request.getKeyPath().toArray(new String[0]);
            node = configTopics.findNode(keyPath);
            if (node == null) {
                return response.responseStatus(ConfigStoreResponseStatus.ResourceNotFoundError)
                        .errorMessage(KEY_NOT_FOUND_ERROR_MESSAGE).build();
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
        log.atDebug().kv(CONTEXT_LOGGING_KEY, context).log("Config IPC config update request");
        UpdateConfigurationResponse.UpdateConfigurationResponseBuilder response = UpdateConfigurationResponse.builder();

        if (Utils.isEmpty(request.getKeyPath())) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest).errorMessage("Key is required")
                    .build();
        }

        if (request.getComponentName() != null && !context.getServiceName().equals(request.getComponentName())) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest)
                    .errorMessage("Cross component updates are not allowed").build();
        }

        Topics serviceTopics = kernel.findServiceTopic(context.getServiceName());
        if (serviceTopics == null) {
            return response.responseStatus(ConfigStoreResponseStatus.InternalError)
                    .errorMessage("Service config not found").build();
        }
        Topics configTopics = serviceTopics.lookupTopics(PARAMETERS_CONFIG_KEY);
        String[] keyPath = request.getKeyPath().toArray(new String[0]);
        Node node = configTopics.findNode(keyPath);
        if (node == null) {
            try {
                configTopics.lookup(keyPath).withValueChecked(request.getNewValue());
            } catch (UnsupportedInputTypeException e) {
                return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest)
                        .errorMessage(e.getMessage()).build();
            }
            return response.responseStatus(ConfigStoreResponseStatus.Success).build();
        }
        // TODO :[P41210581]: UpdateConfiguration API should support updating nested configuration
        if (node instanceof Topics) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest)
                    .errorMessage("Cannot update a " + "non-leaf config node").build();
        }
        if (!(node instanceof Topic)) {
            response.responseStatus(ConfigStoreResponseStatus.InternalError).errorMessage("Node has an unknown type");
            log.atError().log("Somehow Node has an unknown type {}", node.getClass());
        }
        Topic topic = (Topic) node;

        // Perform compare and swap if the customer has specified current value to compare
        if (request.getCurrentValue() != null && !request.getCurrentValue().equals(topic.getOnce())) {
            return response.responseStatus(ConfigStoreResponseStatus.FailedUpdateConditionCheck)
                    .errorMessage("Current value for config is different from the current value needed for the update")
                    .build();
        }

        try {
            Topic updatedNode = topic.withValueChecked(request.getTimestamp(), request.getNewValue());
            if (request.getTimestamp() != updatedNode.getModtime() && !request.getNewValue()
                    .equals(updatedNode.getOnce())) {
                return response.responseStatus(ConfigStoreResponseStatus.FailedUpdateConditionCheck)
                        .errorMessage("Proposed timestamp is older than the config's latest modified timestamp")
                        .build();
            }
        } catch (UnsupportedInputTypeException e) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest)
                    .errorMessage(e.getMessage()).build();
        }

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
        log.atDebug().kv(CONTEXT_LOGGING_KEY, context).log("Config IPC subscribe to config validation request");
        // TODO: [P32540011]: All IPC service requests need input validation
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
                    ConfigStoreServiceOpCodes.VALIDATION_EVENT.ordinal(), ConfigStoreImpl.API_VERSION);
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
        // TODO: [P32540011]: All IPC service requests need input validation
        log.atDebug().kv(CONTEXT_LOGGING_KEY, context).log("Config IPC report config validation request");
        SendConfigurationValidityReportResponse.SendConfigurationValidityReportResponseBuilder response =
                SendConfigurationValidityReportResponse.builder();

        // TODO : [P41210395]: Add mechanism to associate the validity report sent by the component to a
        //  validation request made by the IPC server
        if (!configValidationReportFutures.containsKey(context.getServiceName())) {
            return response.responseStatus(ConfigStoreResponseStatus.InvalidRequest)
                    .errorMessage("Validation request either timed out or was never made").build();
        }

        CompletableFuture<ConfigurationValidityReport> reportFuture =
                configValidationReportFutures.get(context.getServiceName());
        if (!reportFuture.isCancelled()) {
            reportFuture.complete(request.getConfigurationValidityReport());
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
     * @throws ValidateEventRegistrationException throws when triggering requested validation event failed
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public boolean validateConfiguration(String componentName, Map<String, Object> configuration,
                                         CompletableFuture<ConfigurationValidityReport> reportFuture)
            throws ValidateEventRegistrationException {
        for (Map.Entry<ConnectionContext, Consumer<Map<String, Object>>> e : configValidationListeners.entrySet()) {
            if (e.getKey().getServiceName().equals(componentName)) {
                try {
                    e.getValue().accept(configuration);
                    configValidationReportFutures.put(componentName, reportFuture);
                    return true;
                } catch (Exception ex) {
                    // TODO: [P41211196]: Retries, timeouts & and better exception handling in sending server event to
                    //  components
                    throw new ValidateEventRegistrationException(ex);
                }
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
}
