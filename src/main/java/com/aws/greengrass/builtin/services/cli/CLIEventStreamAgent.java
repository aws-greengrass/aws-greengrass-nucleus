/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.cli;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.SerializerFactory;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractCreateLocalDeploymentOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractGetComponentDetailsOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractGetLocalDeploymentStatusOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractListComponentsOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractListLocalDeploymentsOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractRestartComponentOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractStopComponentOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractUpdateRecipesAndArtifactsOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.ComponentDetails;
import software.amazon.awssdk.aws.greengrass.model.CreateLocalDeploymentRequest;
import software.amazon.awssdk.aws.greengrass.model.CreateLocalDeploymentResponse;
import software.amazon.awssdk.aws.greengrass.model.DeploymentStatus;
import software.amazon.awssdk.aws.greengrass.model.GetComponentDetailsRequest;
import software.amazon.awssdk.aws.greengrass.model.GetComponentDetailsResponse;
import software.amazon.awssdk.aws.greengrass.model.GetLocalDeploymentStatusRequest;
import software.amazon.awssdk.aws.greengrass.model.GetLocalDeploymentStatusResponse;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.InvalidArtifactsDirectoryPathError;
import software.amazon.awssdk.aws.greengrass.model.InvalidRecipeDirectoryPathError;
import software.amazon.awssdk.aws.greengrass.model.LifecycleState;
import software.amazon.awssdk.aws.greengrass.model.ListComponentsRequest;
import software.amazon.awssdk.aws.greengrass.model.ListComponentsResponse;
import software.amazon.awssdk.aws.greengrass.model.ListLocalDeploymentsRequest;
import software.amazon.awssdk.aws.greengrass.model.ListLocalDeploymentsResponse;
import software.amazon.awssdk.aws.greengrass.model.LocalDeployment;
import software.amazon.awssdk.aws.greengrass.model.RequestStatus;
import software.amazon.awssdk.aws.greengrass.model.ResourceNotFoundError;
import software.amazon.awssdk.aws.greengrass.model.RestartComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.RestartComponentResponse;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.StopComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.StopComponentResponse;
import software.amazon.awssdk.aws.greengrass.model.UpdateRecipesAndArtifactsRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateRecipesAndArtifactsResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.builtin.services.cli.CLIServiceAgent.LOCAL_DEPLOYMENT_RESOURCE;
import static com.aws.greengrass.builtin.services.cli.CLIServiceAgent.PERSISTENT_LOCAL_DEPLOYMENTS;
import static com.aws.greengrass.componentmanager.ComponentStore.RECIPE_FILE_NAME_FORMAT;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_TYPE_KEY_NAME;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.DEFAULT_GROUP_NAME;
import static com.aws.greengrass.ipc.common.IPCErrorStrings.DEPLOYMENTS_QUEUE_FULL;
import static com.aws.greengrass.ipc.common.IPCErrorStrings.DEPLOYMENTS_QUEUE_NOT_INITIALIZED;

public class CLIEventStreamAgent {

    private static Logger logger = LogManager.getLogger(CLIEventStreamAgent.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Inject
    @Setter (AccessLevel.PACKAGE)
    private Kernel kernel;

    @Inject
    @Setter (AccessLevel.PACKAGE)
    private DeploymentQueue deploymentQueue;

    public GetComponentDetailsHandler getGetComponentDetailsHandler(OperationContinuationHandlerContext context) {
        return new GetComponentDetailsHandler(context);
    }

    public ListComponentsHandler getListComponentsHandler(OperationContinuationHandlerContext context) {
        return new ListComponentsHandler(context);
    }

    public RestartComponentsHandler getRestartComponentsHandler(OperationContinuationHandlerContext context) {
        return new RestartComponentsHandler(context);
    }

    public StopComponentHandler getStopComponentsHandler(OperationContinuationHandlerContext context) {
        return new StopComponentHandler(context);
    }

    public UpdateRecipesAndArtifactsHandler getUpdateRecipesAndArtifactsHandler(
            OperationContinuationHandlerContext context) {
        return new UpdateRecipesAndArtifactsHandler(context);
    }

    public CreateLocalDeploymentHandler getCreateLocalDeploymentHandler(OperationContinuationHandlerContext context,
                                                                        Topics cliServiceConfig) {
        return new CreateLocalDeploymentHandler(context, cliServiceConfig);
    }

    public GetLocalDeploymentStatusHandler getGetLocalDeploymentStatusHandler(
            OperationContinuationHandlerContext context,
            Topics cliServiceConfig) {
        return new GetLocalDeploymentStatusHandler(context, cliServiceConfig);
    }

    public ListLocalDeploymentsHandler getListLocalDeploymentsHandler(OperationContinuationHandlerContext context,
                                                                      Topics cliServiceConfig) {
        return new ListLocalDeploymentsHandler(context, cliServiceConfig);
    }

    /**
     * Persists the local deployment details in the config.
     *
     * @param serviceConfig     CLI service configuration
     * @param deploymentDetails Details of the local deployment to save
     */
    public void persistLocalDeployment(Topics serviceConfig, Map<String, Object> deploymentDetails) {
        Topics localDeployments = serviceConfig.lookupTopics(PERSISTENT_LOCAL_DEPLOYMENTS);
        String deploymentId = (String) deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME);
        Topics localDeploymentDetails = localDeployments.lookupTopics(deploymentId);
        localDeploymentDetails.replaceAndWait(deploymentDetails);
        // TODO: [P41178971]: Implement a limit on no of local deployments to persist status for
    }

    @SuppressFBWarnings("SIC_INNER_SHOULD_BE_STATIC")
    class GetComponentDetailsHandler extends GeneratedAbstractGetComponentDetailsOperationHandler {

        public GetComponentDetailsHandler(OperationContinuationHandlerContext context) {
            super(context);
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        @SuppressWarnings("PMD.PreserveStackTrace")
        public GetComponentDetailsResponse handleRequest(GetComponentDetailsRequest request) {
            validateGetComponentDetailsRequest(request);
            String componentName = request.getComponentName();
            GreengrassService service;
            try {
                service = kernel.locate(componentName);
            } catch (ServiceLoadException e) {
                logger.atError().kv("ComponentName", componentName).setCause(e)
                        .log("Did not find the component with the given name in Greengrass");
                throw new ResourceNotFoundError("Component with name " + componentName
                        + " not found in Greengrass");
            }
            ComponentDetails componentDetails = new ComponentDetails();
            componentDetails.setComponentName(service.getName());
            componentDetails.setState(LifecycleState.valueOf(service.getState().toString()));

            if (service.getServiceConfig().find(VERSION_CONFIG_KEY) != null) {
                componentDetails.setVersion(Coerce.toString(service.getServiceConfig()
                        .find(VERSION_CONFIG_KEY)));
            }
            if (service.getServiceConfig().findInteriorChild(PARAMETERS_CONFIG_KEY) != null) {
                componentDetails
                        .setConfiguration(service.getServiceConfig().findInteriorChild(PARAMETERS_CONFIG_KEY).toPOJO());
            }
            GetComponentDetailsResponse response = new GetComponentDetailsResponse();
            response.setComponentDetails(componentDetails);
            return response;
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

        private void validateGetComponentDetailsRequest(GetComponentDetailsRequest request) {
            validateComponentName(request.getComponentName());
        }
    }

    @SuppressFBWarnings("SIC_INNER_SHOULD_BE_STATIC")
    class ListComponentsHandler extends GeneratedAbstractListComponentsOperationHandler {

        public ListComponentsHandler(OperationContinuationHandlerContext context) {
            super(context);
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        public ListComponentsResponse handleRequest(ListComponentsRequest request) {
            Collection<GreengrassService> services = kernel.orderedDependencies();
            List<ComponentDetails> listOfComponents =
                    services.stream().filter(service -> !service.getName().equals(kernel.getMain().getName()))
                            .map(service -> {
                                ComponentDetails componentDetails = new ComponentDetails();
                                componentDetails.setComponentName(service.getName());
                                componentDetails.setState(LifecycleState.valueOf(service.getState().toString()));

                                if (service.getServiceConfig().find(VERSION_CONFIG_KEY) != null) {
                                    componentDetails.setVersion(Coerce.toString(service.getServiceConfig()
                                                    .find(VERSION_CONFIG_KEY)));
                                }
                                if (service.getServiceConfig().findInteriorChild(PARAMETERS_CONFIG_KEY) != null) {
                                    componentDetails.setConfiguration(service.getServiceConfig()
                                            .findInteriorChild(PARAMETERS_CONFIG_KEY).toPOJO());
                                }
                                return componentDetails;
                            }).collect(Collectors.toList());
            ListComponentsResponse response = new ListComponentsResponse();
            response.setComponents(listOfComponents);
            return response;
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    @SuppressFBWarnings("SIC_INNER_SHOULD_BE_STATIC")
    class RestartComponentsHandler extends GeneratedAbstractRestartComponentOperationHandler {

        public RestartComponentsHandler(OperationContinuationHandlerContext context) {
            super(context);
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        @SuppressWarnings("PMD.PreserveStackTrace")
        public RestartComponentResponse handleRequest(RestartComponentRequest request) {
            validateRestartComponentRequest(request);
            String componentName = request.getComponentName();
            try {
                GreengrassService service = kernel.locate(componentName);
                // TODO: [P41179234]: Add checks that can prevent triggering a component restart/stop
                // Success of this request means restart was triggered successfully
                service.requestRestart();
            } catch (ServiceLoadException e) {
                logger.atError().kv("ComponentName", componentName).setCause(e)
                        .log("Did not find the component with the given name in Greengrass");
                throw new ResourceNotFoundError("Component with name " + componentName
                        + " not found in Greengrass");
            }
            RestartComponentResponse response =  new RestartComponentResponse();
            response.setRestartStatus(RequestStatus.SUCCEEDED);
            return  response;
        }

        private void validateRestartComponentRequest(RestartComponentRequest request) {
            validateComponentName(request.getComponentName());
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    @SuppressFBWarnings("SIC_INNER_SHOULD_BE_STATIC")
    class StopComponentHandler extends GeneratedAbstractStopComponentOperationHandler {

        public StopComponentHandler(OperationContinuationHandlerContext context) {
            super(context);
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        @SuppressWarnings("PMD.PreserveStackTrace")
        public StopComponentResponse handleRequest(StopComponentRequest request) {
            validateStopComponentRequest(request);
            String componentName = request.getComponentName();
            try {
                GreengrassService service = kernel.locate(componentName);
                // TODO: [P41179234]: Add checks that can prevent triggering a component restart/stop
                // Success of this request means stop was triggered successfully
                service.requestStop();
            } catch (ServiceLoadException e) {
                logger.atError().kv("ComponentName", componentName).setCause(e)
                        .log("Did not find the component with the given name in Greengrass");
                throw new ResourceNotFoundError("Component with name " + componentName
                        + " not found in Greengrass");
            }
            StopComponentResponse response = new StopComponentResponse();
            response.setStopStatus(RequestStatus.SUCCEEDED);
            return response;
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

        private void validateStopComponentRequest(StopComponentRequest request) {
            validateComponentName(request.getComponentName());
        }
    }

    class UpdateRecipesAndArtifactsHandler extends GeneratedAbstractUpdateRecipesAndArtifactsOperationHandler {

        public UpdateRecipesAndArtifactsHandler(OperationContinuationHandlerContext context) {
            super(context);
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        @SuppressWarnings("PMD.PreserveStackTrace")
        public UpdateRecipesAndArtifactsResponse handleRequest(UpdateRecipesAndArtifactsRequest request) {
            validateUpdateRecipesAndArtifactsRequest(request);
            Path kernelPackageStorePath = kernel.getNucleusPaths().componentStorePath();
            if (!Utils.isEmpty(request.getRecipeDirectoryPath())) {
                Path recipeDirectoryPath = Paths.get(request.getRecipeDirectoryPath());
                Path kernelRecipeDirectoryPath = kernelPackageStorePath.resolve(ComponentStore.RECIPE_DIRECTORY);
                try {
                    copyRecipes(recipeDirectoryPath, kernelRecipeDirectoryPath);
                } catch (IOException e) {
                    logger.atError().setCause(e).kv("Recipe Directory path", recipeDirectoryPath)
                            .log("Caught exception while updating the recipes");
                    throw new InvalidRecipeDirectoryPathError(e.getMessage());
                }
            }
            if (!Utils.isEmpty(request.getArtifactsDirectoryPath())) {
                Path artifactsDirectoryPath = Paths.get(request.getArtifactsDirectoryPath());
                Path kernelArtifactsDirectoryPath = kernelPackageStorePath.resolve(ComponentStore.ARTIFACT_DIRECTORY);
                try {
                    if (kernelArtifactsDirectoryPath.startsWith(artifactsDirectoryPath)) {
                        String errorString = "Requested artifacts directory path is parent of kernel artifacts "
                                + "directory path. Specify another path to avoid recursive copy";
                        logger.atError().log(errorString);
                        throw new InvalidArtifactsDirectoryPathError(errorString);
                    } else {
                        Utils.copyFolderRecursively(artifactsDirectoryPath, kernelArtifactsDirectoryPath,
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    logger.atError().setCause(e).kv("Artifact Directory path", artifactsDirectoryPath)
                            .log("Caught exception while updating the recipes");
                    throw new InvalidArtifactsDirectoryPathError(e.getMessage());
                }
            }
            return new UpdateRecipesAndArtifactsResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

        private void validateUpdateRecipesAndArtifactsRequest(UpdateRecipesAndArtifactsRequest request) {
            String recipeDirectoryPath = request.getRecipeDirectoryPath();
            String artifactsDirectoryPath = request.getArtifactsDirectoryPath();
            if (StringUtils.isEmpty(recipeDirectoryPath) && StringUtils.isEmpty(artifactsDirectoryPath)) {
                throw new InvalidArgumentsError("Need to provide at least one of the directory "
                        + "paths to update");
            }
        }

        private void copyRecipes(Path from, Path to) throws IOException {
            for (Path r : Files.walk(from).collect(Collectors.toList())) {
                String ext = Utils.extension(r.toString());
                ComponentRecipe recipe = null;
                if (r.toFile().length() == 0) {
                    logger.atInfo().log("Skipping recipe file {} because it is empty", r);
                    continue;
                }
                try {
                    switch (ext.toLowerCase()) {
                        case "yaml":
                        case "yml":
                            recipe = SerializerFactory.getRecipeSerializer().readValue(r.toFile(),
                                    ComponentRecipe.class);
                            break;
                        case "json":
                            recipe = SerializerFactory.getRecipeSerializerJson()
                                    .readValue(r.toFile(), ComponentRecipe.class);
                            break;
                        default:
                            break;
                    }
                } catch (IOException e) {
                    logger.atError().log("Error reading recipe file from {}", r, e);
                }

                if (recipe == null) {
                    continue;
                }

                // Write the recipe as YAML with the proper filename into the store
                Path copyTo = to.resolve(String.format(RECIPE_FILE_NAME_FORMAT, recipe.getComponentName(),
                        recipe.getComponentVersion().getValue()));
                Files.write(copyTo, SerializerFactory.getRecipeSerializer().writeValueAsBytes(recipe));
            }
        }
    }

    class CreateLocalDeploymentHandler extends GeneratedAbstractCreateLocalDeploymentOperationHandler {

        private final Topics cliServiceConfig;

        public CreateLocalDeploymentHandler(OperationContinuationHandlerContext context, Topics cliServiceConfig) {
            super(context);
            this.cliServiceConfig = cliServiceConfig;
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.AvoidCatchingGenericException"})
        public CreateLocalDeploymentResponse handleRequest(CreateLocalDeploymentRequest request) {
            String deploymentId = UUID.randomUUID().toString();
            try {
                //All inputs are valid. If all inputs are empty, then user might just want to retrigger the deployment
                // with new recipes set using the updateRecipesAndArtifacts API.
                Map<String, ConfigurationUpdateOperation> configUpdate = null;
                if (request.getComponentToConfiguration() != null) {
                    configUpdate = request.getComponentToConfiguration().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                        ConfigurationUpdateOperation configUpdateOption = new ConfigurationUpdateOperation();
                        configUpdateOption.setValueToMerge((Map) e.getValue().get("MERGE"));
                        configUpdateOption.setPathsToReset((List) e.getValue().get("RESET"));
                        return configUpdateOption;
                    }));
                }
                LocalOverrideRequest localOverrideRequest = LocalOverrideRequest.builder().requestId(deploymentId)
                        .componentsToMerge(request.getRootComponentVersionsToAdd())
                        .componentsToRemove(request.getRootComponentsToRemove())
                        .requestTimestamp(System.currentTimeMillis())
                        .groupName(request.getGroupName() == null || request.getGroupName()
                                .isEmpty() ? DEFAULT_GROUP_NAME : request.getGroupName())
                        .configurationUpdate(configUpdate).build();
                String deploymentDocument;
                try {
                    deploymentDocument = OBJECT_MAPPER.writeValueAsString(localOverrideRequest);
                } catch (JsonProcessingException e) {
                    logger.atError().setCause(e).log("Caught exception while parsing local deployment request");
                    throw new ServiceError(e.getMessage());
                }
                Deployment deployment = new Deployment(deploymentDocument,
                        Deployment.DeploymentType.LOCAL, deploymentId);
                if (deploymentQueue == null) {
                    logger.atError().log("Deployments queue not initialized");
                    throw new ServiceError(DEPLOYMENTS_QUEUE_NOT_INITIALIZED);
                } else {
                    // save the deployment status as queued
                    LocalDeploymentDetails localDeploymentDetails = new LocalDeploymentDetails();
                    localDeploymentDetails.setDeploymentId(deploymentId);
                    localDeploymentDetails.setDeploymentType(Deployment.DeploymentType.LOCAL);
                    localDeploymentDetails.setStatus(DeploymentStatus.QUEUED);
                    persistLocalDeployment(cliServiceConfig, localDeploymentDetails.convertToMapOfObject());
                    if (deploymentQueue.offer(deployment)) {
                        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                                .log("Submitted local deployment request.");
                        CreateLocalDeploymentResponse createLocalDeploymentResponse =
                                new CreateLocalDeploymentResponse();
                        createLocalDeploymentResponse.setDeploymentId(deploymentId);
                        return createLocalDeploymentResponse;
                    } else {
                        logger.atError().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                                .log("Failed to submit local deployment request because deployment queue is full");
                        throw new ServiceError(DEPLOYMENTS_QUEUE_FULL);
                    }
                }
            } catch (RuntimeException e) {
              logger.atError().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).setCause(e)
                      .log("Caught exception while creating local deployment");
              throw new ServiceError(e.getMessage());
            }
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            //NA
        }
    }

    class GetLocalDeploymentStatusHandler extends GeneratedAbstractGetLocalDeploymentStatusOperationHandler {

        private final Topics cliServiceConfig;

        public GetLocalDeploymentStatusHandler(OperationContinuationHandlerContext context, Topics cliServiceConfig) {
            super(context);
            this.cliServiceConfig = cliServiceConfig;
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        public GetLocalDeploymentStatusResponse handleRequest(GetLocalDeploymentStatusRequest request) {
            validateGetLocalDeploymentStatusRequest(request);
            Topics localDeployments = cliServiceConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS);
            if (localDeployments == null || localDeployments.findTopics(request.getDeploymentId()) == null) {
                ResourceNotFoundError rnf = new ResourceNotFoundError();
                rnf.setMessage("Cannot find deployment");
                rnf.setResourceType(LOCAL_DEPLOYMENT_RESOURCE);
                rnf.setResourceName(request.getDeploymentId());
                throw rnf;
            } else {
                Topics deployment = localDeployments.findTopics(request.getDeploymentId());
                DeploymentStatus status =
                        deploymentStatusFromString(Coerce.toString(deployment.find(DEPLOYMENT_STATUS_KEY_NAME)));
                GetLocalDeploymentStatusResponse response = new GetLocalDeploymentStatusResponse();
                LocalDeployment localDeployment = new LocalDeployment();
                localDeployment.setDeploymentId(request.getDeploymentId());
                localDeployment.setStatus(status);
                response.setDeployment(localDeployment);
                return response;
            }
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        private void validateGetLocalDeploymentStatusRequest(GetLocalDeploymentStatusRequest request) {
            try {
                UUID.fromString(request.getDeploymentId());
            } catch (IllegalArgumentException e) {
                throw new InvalidArgumentsError("Invalid deploymentId format received. DeploymentId is a UUID");
            }
        }
    }

    class ListLocalDeploymentsHandler extends GeneratedAbstractListLocalDeploymentsOperationHandler {

        private final Topics cliServiceConfig;

        public ListLocalDeploymentsHandler(OperationContinuationHandlerContext context, Topics cliServiceConfig) {
            super(context);
            this.cliServiceConfig = cliServiceConfig;
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        public ListLocalDeploymentsResponse handleRequest(ListLocalDeploymentsRequest request) {
            List<LocalDeployment> persistedDeployments = new ArrayList<>();
            Topics localDeployments = cliServiceConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS);
            if (localDeployments != null) {
                localDeployments.forEach(topic -> {
                    Topics topics = (Topics) topic;
                    LocalDeployment localDeployment = new LocalDeployment();
                    localDeployment.setDeploymentId(topics.getName());
                    localDeployment.setStatus(deploymentStatusFromString(
                            Coerce.toString(topics.find(DEPLOYMENT_STATUS_KEY_NAME))));
                    persistedDeployments.add(localDeployment);
                });
            }
            ListLocalDeploymentsResponse response = new ListLocalDeploymentsResponse();
            response.setLocalDeployments(persistedDeployments);
            return response;
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    private void validateComponentName(String componentName) {
        if (Utils.isEmpty(componentName)) {
            throw new InvalidArgumentsError("Component name cannot be empty");
        }
    }

    private DeploymentStatus deploymentStatusFromString(String status) {
        for (DeploymentStatus ds: DeploymentStatus.values()) {
            if (ds.getValue().equals(status.toUpperCase())) {
                return ds;
            }
        }
        return null;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class LocalDeploymentDetails {
        @JsonProperty(DEPLOYMENT_ID_KEY_NAME)
        private String deploymentId;
        @JsonProperty(DEPLOYMENT_STATUS_KEY_NAME)
        private DeploymentStatus status;
        @JsonProperty(DEPLOYMENT_TYPE_KEY_NAME)
        private Deployment.DeploymentType deploymentType;

        /**
         * Returns a map of string to object representing the deployment details.
         *
         * @return Map of string to object
         */
        public Map<String, Object> convertToMapOfObject() {
            Map<String, Object> deploymentDetails = new HashMap<>();
            deploymentDetails.put(DEPLOYMENT_ID_KEY_NAME, deploymentId);
            deploymentDetails.put(DEPLOYMENT_STATUS_KEY_NAME, status.getValue());
            deploymentDetails.put(DEPLOYMENT_TYPE_KEY_NAME, Coerce.toString(deploymentType));
            return deploymentDetails;
        }
    }
}
