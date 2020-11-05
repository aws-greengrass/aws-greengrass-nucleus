/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.configstore;

import com.amazonaws.services.evergreen.model.InternalServerException;
import com.aws.greengrass.builtin.services.configstore.exceptions.ValidateEventRegistrationException;
import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UnsupportedInputTypeException;
import com.aws.greengrass.config.Watcher;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractGetConfigurationOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSendConfigurationValidityReportOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToValidateConfigurationUpdatesOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractUpdateConfigurationOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationUpdateEvents;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityReport;
import software.amazon.awssdk.aws.greengrass.model.FailedUpdateConditionCheckError;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationResponse;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.ResourceNotFoundError;
import software.amazon.awssdk.aws.greengrass.model.SendConfigurationValidityReportRequest;
import software.amazon.awssdk.aws.greengrass.model.SendConfigurationValidityReportResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToConfigurationUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToConfigurationUpdateResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.UpdateConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateConfigurationResponse;
import software.amazon.awssdk.aws.greengrass.model.ValidateConfigurationUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.ValidateConfigurationUpdateEvents;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.StreamEventPublisher;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

public class ConfigStoreIPCEventStreamAgent {
    private static final Logger logger = LogManager.getLogger(ConfigStoreIPCEventStreamAgent.class);
    private static final String KEY_NOT_FOUND_ERROR_MESSAGE = "Key not found";
    private static final String SERVICE_NAME = "service-name";
    @Getter(AccessLevel.PACKAGE)
    private final ConcurrentHashMap<String, Set<StreamEventPublisher<ConfigurationUpdateEvents>>>
            configUpdateListeners = new ConcurrentHashMap<>();
    @Getter(AccessLevel.PACKAGE)
    private final ConcurrentHashMap<String, BiConsumer<String, Map<String, Object>>>
            configValidationListeners = new ConcurrentHashMap<>();
    // Map of component + deployment id --> future to complete with validation status received from service in response
    // to validate event
    @Getter(AccessLevel.PACKAGE)
    private final Map<Pair<String, String>, CompletableFuture<ConfigurationValidityReport>>
            configValidationReportFutures = new ConcurrentHashMap<>();

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private Kernel kernel;

    public ConfigurationUpdateOperationHandler getConfigurationUpdateHandler(
            OperationContinuationHandlerContext context) {
        return new ConfigurationUpdateOperationHandler(context);
    }

    public ValidateConfigurationUpdatesOperationHandler getValidateConfigurationUpdatesHandler(
            OperationContinuationHandlerContext context) {
        return new ValidateConfigurationUpdatesOperationHandler(context);
    }

    public UpdateConfigurationOperationHandler getUpdateConfigurationHandler(
            OperationContinuationHandlerContext context) {
        return new UpdateConfigurationOperationHandler(context);
    }

    public GetConfigurationOperationHandler getGetConfigurationHandler(
            OperationContinuationHandlerContext context) {
        return new GetConfigurationOperationHandler(context);
    }

    public SendConfigurationValidityReportOperationHandler getSendConfigurationValidityReportHandler(
            OperationContinuationHandlerContext context) {
        return new SendConfigurationValidityReportOperationHandler(context);
    }

    class SendConfigurationValidityReportOperationHandler extends
            GeneratedAbstractSendConfigurationValidityReportOperationHandler {
        private final String serviceName;

        protected SendConfigurationValidityReportOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        /**
         * Handle user service's response to config validation request.
         *
         * @param request request to report validation status for config
         * @return response data
         */
        @Override
        public SendConfigurationValidityReportResponse handleRequest(SendConfigurationValidityReportRequest request) {
            // TODO: [P32540011]: All IPC service requests need input validation
            logger.atDebug().kv(SERVICE_NAME, serviceName).log("Config IPC report config validation request");

            if (request.getConfigurationValidityReport().getDeploymentId() == null) {
                throw new InvalidArgumentsError(
                        "Cannot accept configuration validity report, the deployment ID provided was null");
            }
            Pair<String, String> serviceDeployment =
                    new Pair<>(serviceName, request.getConfigurationValidityReport().getDeploymentId());
            CompletableFuture<ConfigurationValidityReport> reportFuture =
                    configValidationReportFutures.get(serviceDeployment);
            if (reportFuture == null) {
                throw new InvalidArgumentsError("Validation request either timed out or was never made");
            }

            if (!reportFuture.isCancelled()) {
                reportFuture.complete(request.getConfigurationValidityReport());
            }
            configValidationReportFutures.remove(serviceDeployment);

            return new SendConfigurationValidityReportResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    class GetConfigurationOperationHandler extends GeneratedAbstractGetConfigurationOperationHandler {
        private final String serviceName;

        protected GetConfigurationOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        /**
         * Read specified key from the service's dynamic config.
         *
         * @param request   request
         * @return response data
         */
        @Override
        public GetConfigurationResponse handleRequest(GetConfigurationRequest request) {
            logger.atDebug().kv(SERVICE_NAME, serviceName).log("Config IPC get config request");
            String finalServiceName = request.getComponentName() == null ? this.serviceName
                    : request.getComponentName();
            Topics serviceTopics = kernel.findServiceTopic(finalServiceName);

            if (serviceTopics == null) {
                throw new ResourceNotFoundError(KEY_NOT_FOUND_ERROR_MESSAGE);
            }

            Topics configTopics = serviceTopics.findInteriorChild(PARAMETERS_CONFIG_KEY);
            if (configTopics == null) {
                throw new ResourceNotFoundError(KEY_NOT_FOUND_ERROR_MESSAGE);
            }

            Node node;
            if (Utils.isEmpty(request.getKeyPath())) {
                // Request is for reading all configuration
                node = configTopics;
            } else {
                String[] keyPath = request.getKeyPath().toArray(new String[0]);
                node = configTopics.findNode(keyPath);
                if (node == null) {
                    throw new ResourceNotFoundError(KEY_NOT_FOUND_ERROR_MESSAGE);
                }
            }

            GetConfigurationResponse response = new GetConfigurationResponse();
            response.setComponentName(finalServiceName);
            if (node instanceof Topic) {
                Map<String, Object> map = new HashMap<>();
                map.put(node.getName(), ((Topic) node).getOnce());
                response.setValue(map);
            } else if (node instanceof Topics) {
                response.setValue(((Topics) node).toPOJO());
            } else {
                logger.atError().log("Somehow Node has an unknown type {}", node.getClass());
                throw new InternalServerException("Node has an unknown type");
            }
            return response;
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    class UpdateConfigurationOperationHandler extends GeneratedAbstractUpdateConfigurationOperationHandler {
        private final String serviceName;

        protected UpdateConfigurationOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        /**
         * Update specified key in the service's configuration.
         *
         * @param request update config request
         * @return response data
         */
        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public UpdateConfigurationResponse handleRequest(UpdateConfigurationRequest request) {
            logger.atDebug().kv(SERVICE_NAME, serviceName).log("Config IPC config update request");
            if (Utils.isEmpty(request.getKeyPath())) {
                throw new InvalidArgumentsError("Key is required");
            }

            if (request.getComponentName() != null && !serviceName.equals(request.getComponentName())) {
                throw new InvalidArgumentsError("Cross component updates are not allowed");
            }

            Topics serviceTopics = kernel.findServiceTopic(serviceName);
            if (serviceTopics == null) {
                throw new InvalidArgumentsError("Service config not found");
            }
            Topics configTopics = serviceTopics.lookupTopics(PARAMETERS_CONFIG_KEY);
            String[] keyPath = request.getKeyPath().toArray(new String[0]);
            Node node = configTopics.findNode(keyPath);
            if (node == null) {
                try {
                    configTopics.lookup(keyPath)
                            .withValueChecked(request.getNewValue().get(keyPath[keyPath.length - 1]));
                } catch (UnsupportedInputTypeException e) {
                    throw new InvalidArgumentsError(e.getMessage());
                }
                return new UpdateConfigurationResponse();
            }
            // TODO :[P41210581]: UpdateConfiguration API should support updating nested configuration
            if (node instanceof Topics) {
                throw new InvalidArgumentsError("Cannot update a non-leaf config node");
            }
            if (!(node instanceof Topic)) {
                logger.atError().kv(SERVICE_NAME, serviceName)
                        .log("Somehow Node has an unknown type {}", node.getClass());
                throw new InvalidArgumentsError("Node has an unknown type");
            }
            Topic topic = (Topic) node;

            // Perform compare and swap if the customer has specified current value to compare
            if (request.getOldValue() != null && request.getOldValue().get(topic.getName()) != null
                    && !request.getOldValue().get(topic.getName()).equals(topic.getOnce())) {
                throw new FailedUpdateConditionCheckError(
                        "Current value for config is different from the current value needed for the update");
            }

            try {
                Topic updatedNode = topic.withValueChecked(request.getTimestamp().toEpochMilli(),
                        request.getNewValue().get(topic.getName()));
                if (request.getTimestamp().toEpochMilli() != updatedNode.getModtime() && !request.getNewValue()
                        .equals(updatedNode.getOnce())) {
                    throw new FailedUpdateConditionCheckError(
                            "Proposed timestamp is older than the config's latest modified timestamp");
                }
            } catch (UnsupportedInputTypeException e) {
                throw new InvalidArgumentsError(e.getMessage());
            }

            return new UpdateConfigurationResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    public class ConfigurationUpdateOperationHandler extends
            GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler {
        private final String serviceName;
        private Node subscribedToNode;
        private Watcher subscribedToWatcher;

        public ConfigurationUpdateOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            logger.atInfo().kv(SERVICE_NAME, serviceName)
                    .log("Stream closed for subscribeToConfigurationUpdate for {}", serviceName);
            if (subscribedToNode != null) {
                subscribedToNode.remove(subscribedToWatcher);
            }
            configUpdateListeners.get(serviceName).remove(this);
            if (configUpdateListeners.get(serviceName).isEmpty()) {
                configUpdateListeners.remove(serviceName);
            }
        }

        @Override
        public SubscribeToConfigurationUpdateResponse handleRequest(SubscribeToConfigurationUpdateRequest request) {
            String componentName = request.getComponentName() == null ? this.serviceName : request.getComponentName();

            Topics serviceTopics = kernel.findServiceTopic(componentName);
            if (serviceTopics == null) {
                throw new ResourceNotFoundError(KEY_NOT_FOUND_ERROR_MESSAGE);
            }

            Topics configurationTopics = serviceTopics.lookupTopics(PARAMETERS_CONFIG_KEY);
            if (configurationTopics == null) {
                throw new ResourceNotFoundError(KEY_NOT_FOUND_ERROR_MESSAGE);
            }

            Node subscribeTo = getNodeToSubscribeTo(configurationTopics, request.getKeyPath());
            if (subscribeTo == null) {
                throw new ResourceNotFoundError(KEY_NOT_FOUND_ERROR_MESSAGE);
            }

            Optional<Watcher> watcher = registerWatcher(subscribeTo, componentName);
            if (!watcher.isPresent()) {
                throw new InternalServerException(KEY_NOT_FOUND_ERROR_MESSAGE);
            }

            logger.atInfo().kv(SERVICE_NAME, serviceName)
                    .log("{} subscribed to configuration update", serviceName);
            subscribedToNode = subscribeTo;
            subscribedToWatcher = watcher.get();
            configUpdateListeners.putIfAbsent(serviceName, ConcurrentHashMap.newKeySet());
            configUpdateListeners.get(serviceName).add(this);

            return new SubscribeToConfigurationUpdateResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            // NA

        }

        private Optional<Watcher> registerWatcher(Node subscribeTo, String componentName) {
            if (subscribeTo instanceof Topics) {
                ChildChanged childChanged =
                        (whatHappened, node) -> handleConfigNodeUpdate(whatHappened, node, componentName);

                ((Topics) subscribeTo).subscribe(childChanged);
                return Optional.of(childChanged);

            } else if (subscribeTo instanceof Topic) {
                Subscriber subscriber =
                        (whatHappened, topic) -> handleConfigNodeUpdate(whatHappened, topic, componentName);

                ((Topic) subscribeTo).subscribe(subscriber);
                return Optional.of(subscriber);
            }
            return Optional.empty();
        }

        private void handleConfigNodeUpdate(WhatHappened whatHappened, Node changedNode, String componentName) {
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
            String[] keyPath = Arrays.copyOfRange(changedNode.path(), configurationTopicsIndex + 1,
                    changedNode.path().length);

            sendConfigUpdateToListener(componentName).accept(keyPath);
        }

        private Consumer<String[]> sendConfigUpdateToListener(String componentName) {
            return changedKeyPath -> {
                ConfigurationUpdateEvents configurationUpdateEvents = new ConfigurationUpdateEvents();
                ConfigurationUpdateEvent valueChangedEvent = new ConfigurationUpdateEvent();
                valueChangedEvent.setComponentName(componentName);
                valueChangedEvent.setKeyPath(Arrays.asList(changedKeyPath));
                configurationUpdateEvents.setConfigurationUpdateEvent(valueChangedEvent);
                logger.atInfo().kv(SERVICE_NAME, serviceName)
                        .log("Sending component {}'s updated config key {}", componentName, changedKeyPath);

                this.sendStreamEvent(configurationUpdateEvents);
            };
        }

        private Node getNodeToSubscribeTo(Topics configurationTopics, List<String> keyPath) {
            Node subscribeTo = configurationTopics;
            if (keyPath != null && !keyPath.isEmpty()) {
                subscribeTo = configurationTopics.findNode(keyPath.toArray(new String[0]));
            }
            return subscribeTo;
        }
    }

    class ValidateConfigurationUpdatesOperationHandler extends
            GeneratedAbstractSubscribeToValidateConfigurationUpdatesOperationHandler {

        private final String serviceName;

        protected ValidateConfigurationUpdatesOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            configValidationListeners.remove(serviceName);
        }

        @Override
        public SubscribeToValidateConfigurationUpdatesResponse handleRequest(
                SubscribeToValidateConfigurationUpdatesRequest request) {
            // TODO: [P32540011]: All IPC service requests need input validation
            configValidationListeners.computeIfAbsent(serviceName, key -> sendConfigValidationEvent());
            logger.atInfo().kv(SERVICE_NAME, serviceName).log("Config IPC subscribe to config validation request");
            return new SubscribeToValidateConfigurationUpdatesResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            // NA
        }

        private BiConsumer<String, Map<String, Object>> sendConfigValidationEvent() {
            return (deploymentId, configuration) -> {
                ValidateConfigurationUpdateEvents events = new ValidateConfigurationUpdateEvents();
                ValidateConfigurationUpdateEvent validationEvent = new ValidateConfigurationUpdateEvent();
                validationEvent.setConfiguration(configuration);
                validationEvent.setDeploymentId(deploymentId);
                events.setValidateConfigurationUpdateEvent(validationEvent);
                logger.atDebug().kv(SERVICE_NAME, serviceName)
                        .log("Requesting validation for component config {}", configuration);

                this.sendStreamEvent(events);
            };
        }
    }

    /**
     * Trigger a validate event to service/component, typically used during deployments.
     *
     * @param componentName service/component to send validate event to
     * @param deploymentId deployment id which is being validated
     * @param configuration new component configuration to validate
     * @param reportFuture  future to track validation report in response to the event
     * @return true if the service has registered a validator, false if not
     * @throws ValidateEventRegistrationException throws when triggering requested validation event failed
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public boolean validateConfiguration(String componentName, String deploymentId, Map<String, Object> configuration,
                                         CompletableFuture<ConfigurationValidityReport> reportFuture)
            throws ValidateEventRegistrationException {
        for (Map.Entry<String, BiConsumer<String, Map<String, Object>>> e : configValidationListeners.entrySet()) {
            if (e.getKey().equals(componentName)) {
                try {
                    e.getValue().accept(deploymentId, configuration);
                    configValidationReportFutures.put(new Pair<>(componentName, deploymentId), reportFuture);
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
     * @param deploymentId  the deployment id which is being validated
     * @param componentName component name to abandon validation for
     * @param reportFuture  tracking future for validation report to abandon
     * @return true if abandon request was successful
     */
    public boolean discardValidationReportTracker(String deploymentId, String componentName,
                                                  CompletableFuture<ConfigurationValidityReport> reportFuture) {
        return configValidationReportFutures.remove(new Pair<>(componentName, deploymentId), reportFuture);
    }
}
