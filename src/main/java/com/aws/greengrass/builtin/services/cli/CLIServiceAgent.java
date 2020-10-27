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
import com.aws.greengrass.ipc.services.cli.exceptions.ComponentNotFoundError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidArgumentsError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidArtifactsDirectoryPathError;
import com.aws.greengrass.ipc.services.cli.exceptions.InvalidRecipesDirectoryPathError;
import com.aws.greengrass.ipc.services.cli.exceptions.ResourceNotFoundError;
import com.aws.greengrass.ipc.services.cli.exceptions.ServiceError;
import com.aws.greengrass.ipc.services.cli.models.ComponentDetails;
import com.aws.greengrass.ipc.services.cli.models.CreateLocalDeploymentRequest;
import com.aws.greengrass.ipc.services.cli.models.CreateLocalDeploymentResponse;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsRequest;
import com.aws.greengrass.ipc.services.cli.models.GetComponentDetailsResponse;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusRequest;
import com.aws.greengrass.ipc.services.cli.models.GetLocalDeploymentStatusResponse;
import com.aws.greengrass.ipc.services.cli.models.LifecycleState;
import com.aws.greengrass.ipc.services.cli.models.ListComponentsResponse;
import com.aws.greengrass.ipc.services.cli.models.ListLocalDeploymentResponse;
import com.aws.greengrass.ipc.services.cli.models.LocalDeployment;
import com.aws.greengrass.ipc.services.cli.models.RequestStatus;
import com.aws.greengrass.ipc.services.cli.models.RestartComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.RestartComponentResponse;
import com.aws.greengrass.ipc.services.cli.models.StopComponentRequest;
import com.aws.greengrass.ipc.services.cli.models.StopComponentResponse;
import com.aws.greengrass.ipc.services.cli.models.UpdateRecipesAndArtifactsRequest;
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
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

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
import javax.annotation.Nonnull;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.ComponentStore.RECIPE_FILE_NAME_FORMAT;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_TYPE_KEY_NAME;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.DEFAULT_GROUP_NAME;
import static com.aws.greengrass.ipc.common.IPCErrorStrings.DEPLOYMENTS_QUEUE_FULL;
import static com.aws.greengrass.ipc.common.IPCErrorStrings.DEPLOYMENTS_QUEUE_NOT_INITIALIZED;

public class CLIServiceAgent {

    public static final String PERSISTENT_LOCAL_DEPLOYMENTS = "LocalDeployments";
    public static final String LOCAL_DEPLOYMENT_RESOURCE = "LocalDeployment";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Kernel kernel;

    private final DeploymentQueue deploymentQueue;

    private static final Logger logger = LogManager.getLogger(CLIServiceAgent.class);

    @Inject
    public CLIServiceAgent(Kernel kernel, DeploymentQueue deploymentQueue) {
        this.kernel = kernel;
        this.deploymentQueue = deploymentQueue;
    }

    /**
     * Get the details of a component with the given name.
     *
     * @param request {@link GetComponentDetailsRequest}
     * @return {@link GetComponentDetailsResponse}
     * @throws InvalidArgumentsError  thrown when empty component name is received
     * @throws ComponentNotFoundError Thrown when given component does not exist
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public GetComponentDetailsResponse getComponentDetails(GetComponentDetailsRequest request)
            throws InvalidArgumentsError, ComponentNotFoundError {
        validateGetComponentDetailsRequest(request);
        String componentName = request.getComponentName();
        GreengrassService service;
        try {
            service = kernel.locate(componentName);
        } catch (ServiceLoadException e) {
            logger.atError().kv("ComponentName", componentName).setCause(e)
                    .log("Did not find the component with the given name in Greengrass");
            throw new ComponentNotFoundError("Component with name " + componentName + " not found in Greengrass");
        }
        ComponentDetails componentDetails = ComponentDetails.builder().componentName(service.getName())
                .state(LifecycleState.valueOf(service.getState().toString())).build();
        if (service.getServiceConfig().find(VERSION_CONFIG_KEY) != null) {
            componentDetails.setVersion(Coerce.toString(service.getServiceConfig().find(VERSION_CONFIG_KEY).getOnce()));
        }
        if (service.getServiceConfig().findInteriorChild(CONFIGURATION_CONFIG_KEY) != null) {
            componentDetails
                    .setConfiguration(service.getServiceConfig().findInteriorChild(CONFIGURATION_CONFIG_KEY).toPOJO());
        }
        return GetComponentDetailsResponse.builder().componentDetails(componentDetails).build();
    }

    /**
     * Returns the list of all the components running in the Greengrass.
     *
     * @return {@link ListComponentsResponse}
     */
    public ListComponentsResponse listComponents() {
        Collection<GreengrassService> services = kernel.orderedDependencies();
        List<ComponentDetails> listOfComponents =
                services.stream().filter(service -> !service.getName().equals(kernel.getMain().getName()))
                        .map(service -> {
                            ComponentDetails componentDetails =
                                    ComponentDetails.builder().componentName(service.getName())
                                            .state(LifecycleState.valueOf(service.getState().toString())).build();
                            if (service.getServiceConfig().find(VERSION_CONFIG_KEY) != null) {
                                componentDetails.setVersion(
                                        Coerce.toString(service.getServiceConfig().find(VERSION_CONFIG_KEY).getOnce()));
                            }
                            if (service.getServiceConfig().findInteriorChild(CONFIGURATION_CONFIG_KEY) != null) {
                                componentDetails.setConfiguration(
                                        service.getServiceConfig().findInteriorChild(CONFIGURATION_CONFIG_KEY)
                                                .toPOJO());
                            }
                            return componentDetails;
                        }).collect(Collectors.toList());
        return ListComponentsResponse.builder().components(listOfComponents).build();
    }

    /**
     * Restart a component with the given name.
     *
     * @param request {@link RestartComponentRequest}
     * @return {@link RestartComponentResponse}
     * @throws InvalidArgumentsError  thrown when empty component name is received
     * @throws ComponentNotFoundError thrown when component does not exist
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public RestartComponentResponse restartComponent(RestartComponentRequest request)
            throws InvalidArgumentsError, ComponentNotFoundError {
        validateRestartComponentRequest(request);
        String componentName = request.getComponentName();
        try {
            GreengrassService service = kernel.locate(componentName);
            // GG_NEEDS_REVIEW: TODO: Add any checks that can prevent triggering a restart. Right now they do not exist.
            // Success of this request means restart was triggered successfully
            service.requestRestart();
        } catch (ServiceLoadException e) {
            logger.atError().kv("ComponentName", componentName).setCause(e)
                    .log("Did not find the component with the given name in Greengrass");
            throw new ComponentNotFoundError("Component with name " + componentName + " not found in Greengrass");
        }
        return RestartComponentResponse.builder().requestStatus(RequestStatus.SUCCEEDED).build();
    }

    /**
     * Stop a component with the given name.
     *
     * @param request {@link StopComponentRequest}
     * @return {@link StopComponentResponse}
     * @throws InvalidArgumentsError  thrown when empty component name is received
     * @throws ComponentNotFoundError thrown when component does not exist
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public StopComponentResponse stopComponent(StopComponentRequest request)
            throws InvalidArgumentsError, ComponentNotFoundError {
        validateStopComponentRequest(request);
        String componentName = request.getComponentName();
        try {
            GreengrassService service = kernel.locate(componentName);
            // GG_NEEDS_REVIEW: TODO: Add any checks that can prevent triggering a stop. Right now they do not exist.
            // Success of this request means stop was triggered successfully
            service.requestStop();
        } catch (ServiceLoadException e) {
            logger.atError().kv("ComponentName", componentName).setCause(e)
                    .log("Did not find the component with the given name in Greengrass");
            throw new ComponentNotFoundError("Component with name " + componentName + " not found in Greengrass");
        }
        return StopComponentResponse.builder().requestStatus(RequestStatus.SUCCEEDED).build();
    }

    /**
     * Copy the recipes and artifacts from given directory path to the kernel package store.
     *
     * @param request {@link UpdateRecipesAndArtifactsRequest}
     * @throws InvalidArgumentsError              thrown when both arguments are empty
     * @throws InvalidRecipesDirectoryPathError   thrown when the recipe directory path is invalid or kernel does not
     *                                            have permissions to access it.
     * @throws InvalidArtifactsDirectoryPathError thrown when the artifacts directory path is invalid or kernel does not
     *                                            have permissions to access it.
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public void updateRecipesAndArtifacts(UpdateRecipesAndArtifactsRequest request)
            throws InvalidArgumentsError, InvalidRecipesDirectoryPathError, InvalidArtifactsDirectoryPathError {
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
                throw new InvalidRecipesDirectoryPathError(e.getMessage());
            }
        }
        if (!Utils.isEmpty(request.getArtifactDirectoryPath())) {
            Path artifactsDirectoryPath = Paths.get(request.getArtifactDirectoryPath());
            Path kernelArtifactsDirectoryPath = kernelPackageStorePath.resolve(ComponentStore.ARTIFACT_DIRECTORY);
            try {
                Utils.copyFolderRecursively(artifactsDirectoryPath, kernelArtifactsDirectoryPath,
                                            StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.atError().setCause(e).kv("Artifact Directory path", artifactsDirectoryPath)
                        .log("Caught exception while updating the recipes");
                throw new InvalidArtifactsDirectoryPathError(e.getMessage());
            }
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
                        recipe = SerializerFactory.getRecipeSerializer().readValue(r.toFile(), ComponentRecipe.class);
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

    /**
     * Creates a local deployment.
     *
     * @param serviceConfig Service config for CLI
     * @param request       {@link CreateLocalDeploymentRequest}
     * @return {@link CreateLocalDeploymentResponse}
     * @throws ServiceError thrown when deployment cannot be queued
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public CreateLocalDeploymentResponse createLocalDeployment(Topics serviceConfig,
            @Nonnull CreateLocalDeploymentRequest request) throws ServiceError {
        // All inputs are valid. If all inputs are empty, then user might just want to retrigger the deployment with new
        // recipes set using the updateRecipesAndArtifacts API.
        String deploymentId = UUID.randomUUID().toString();
        Map<String, ConfigurationUpdateOperation> configUpdate = null;
        if (request.getConfigurationUpdate() != null) {
            configUpdate = request.getConfigurationUpdate().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                        ConfigurationUpdateOperation configUpdateOption = new ConfigurationUpdateOperation();
                        configUpdateOption.setValueToMerge((Map) e.getValue().get("MERGE"));
                        configUpdateOption.setPathsToReset((List) e.getValue().get("RESET"));
                        return configUpdateOption;
                    }));
        }

        LocalOverrideRequest localOverrideRequest = LocalOverrideRequest.builder().requestId(deploymentId)
                .componentsToMerge(request.getRootComponentVersionsToAdd())
                .componentsToRemove(request.getRootComponentsToRemove()).requestTimestamp(System.currentTimeMillis())
                .groupName(request.getGroupName() == null || request.getGroupName().isEmpty() ? DEFAULT_GROUP_NAME
                                   : request.getGroupName())
                .componentNameToConfig(request.getComponentToConfiguration()).configurationUpdate(configUpdate).build();
        String deploymentDocument;
        try {
            deploymentDocument = OBJECT_MAPPER.writeValueAsString(localOverrideRequest);
        } catch (JsonProcessingException e) {
            logger.atError().setCause(e).log("Caught exception while parsing local deployment request");
            throw new ServiceError(e.getMessage());
        }
        Deployment deployment = new Deployment(deploymentDocument, Deployment.DeploymentType.LOCAL, deploymentId);
        if (deploymentQueue == null) {
            logger.atError().log("Deployments queue not initialized");
            throw new ServiceError(DEPLOYMENTS_QUEUE_NOT_INITIALIZED);
        } else {
            // save the deployment status as queued
            LocalDeploymentDetails localDeploymentDetails = new LocalDeploymentDetails();
            localDeploymentDetails.setDeploymentId(deploymentId);
            localDeploymentDetails.setDeploymentType(Deployment.DeploymentType.LOCAL);
            localDeploymentDetails.setStatus(DeploymentStatus.QUEUED);
            persistLocalDeployment(serviceConfig, localDeploymentDetails.convertToMapOfObject());
            if (deploymentQueue.offer(deployment)) {
                logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).log("Submitted local deployment request.");
                return CreateLocalDeploymentResponse.builder().deploymentId(deploymentId).build();
            } else {
                logger.atError().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                        .log("Failed to submit local deployment request because deployment queue is full");
                throw new ServiceError(DEPLOYMENTS_QUEUE_FULL);
            }
        }
    }

    /**
     * Get status of local deployment with the given deploymentId.
     *
     * @param request       {@link GetLocalDeploymentStatusRequest}
     * @param serviceConfig Cli service configuration
     * @return {@link GetLocalDeploymentStatusResponse}
     * @throws InvalidArgumentsError thrown when invalid deploymentId format is received
     * @throws ResourceNotFoundError thrown when deployment with given Id not found
     */
    public GetLocalDeploymentStatusResponse getLocalDeploymentStatus(Topics serviceConfig,
            GetLocalDeploymentStatusRequest request) throws InvalidArgumentsError, ResourceNotFoundError {
        validateGetLocalDeploymentStatusRequest(request);
        Topics localDeployments = serviceConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS);
        if (localDeployments == null || localDeployments.findTopics(request.getDeploymentId()) == null) {
            throw new ResourceNotFoundError("Cannot find deployment", LOCAL_DEPLOYMENT_RESOURCE,
                                            request.getDeploymentId());
        } else {
            Topics deployment = localDeployments.findTopics(request.getDeploymentId());
            DeploymentStatus status =
                    Coerce.toEnum(DeploymentStatus.class, deployment.find(DEPLOYMENT_STATUS_KEY_NAME));
            return GetLocalDeploymentStatusResponse.builder().deployment(
                    LocalDeployment.builder().deploymentId(request.getDeploymentId()).status(status).build()).build();
        }
    }

    /**
     * Lists last 5 local deployments.
     *
     * @param serviceConfig CLI service configuration
     * @return {@link ListLocalDeploymentResponse}
     */
    public ListLocalDeploymentResponse listLocalDeployments(Topics serviceConfig) {
        List<LocalDeployment> persistedDeployments = new ArrayList<>();
        Topics localDeployments = serviceConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS);
        localDeployments.forEach(topic -> {
            Topics topics = (Topics) topic;
            persistedDeployments.add(LocalDeployment.builder().deploymentId(topics.getName())
                                             .status(Coerce.toEnum(DeploymentStatus.class,
                                                                   topics.find(DEPLOYMENT_STATUS_KEY_NAME))).build());
        });
        return ListLocalDeploymentResponse.builder().localDeployments(persistedDeployments).build();
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
        // GG_NEEDS_REVIEW: TODO: Remove the succeeded deployments if the number of deployments have exceeded max limit
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private void validateGetLocalDeploymentStatusRequest(GetLocalDeploymentStatusRequest request)
            throws InvalidArgumentsError {
        try {
            UUID.fromString(request.getDeploymentId());
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentsError("Invalid deploymentId format received. DeploymentId is a UUID");
        }
    }

    private void validateUpdateRecipesAndArtifactsRequest(UpdateRecipesAndArtifactsRequest request)
            throws InvalidArgumentsError {
        String recipeDirectoryPath = request.getRecipeDirectoryPath();
        String artifactsDirectoryPath = request.getArtifactDirectoryPath();
        if (StringUtils.isEmpty(recipeDirectoryPath) && StringUtils.isEmpty(artifactsDirectoryPath)) {
            throw new InvalidArgumentsError("Need to provide at least one of the directory paths to update");
        }
    }

    private void validateStopComponentRequest(StopComponentRequest request) throws InvalidArgumentsError {
        validateComponentName(request.getComponentName());
    }

    private void validateRestartComponentRequest(RestartComponentRequest request) throws InvalidArgumentsError {
        validateComponentName(request.getComponentName());
    }

    private void validateGetComponentDetailsRequest(GetComponentDetailsRequest request) throws InvalidArgumentsError {
        validateComponentName(request.getComponentName());
    }

    private void validateComponentName(String componentName) throws InvalidArgumentsError {
        if (StringUtils.isEmpty(componentName)) {
            throw new InvalidArgumentsError("Component name cannot be empty");
        }
    }

    @Data
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
            deploymentDetails.put(DEPLOYMENT_STATUS_KEY_NAME, Coerce.toString(status));
            deploymentDetails.put(DEPLOYMENT_TYPE_KEY_NAME, Coerce.toString(deploymentType));
            return deploymentDetails;
        }
    }
}
