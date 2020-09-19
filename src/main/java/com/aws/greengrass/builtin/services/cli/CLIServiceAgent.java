package com.aws.greengrass.builtin.services.cli;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.config.Topics;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENTS_QUEUE;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.DEFAULT_GROUP_NAME;
import static com.aws.greengrass.ipc.common.IPCErrorStrings.DEPLOYMENTS_QUEUE_FULL;
import static com.aws.greengrass.ipc.common.IPCErrorStrings.DEPLOYMENTS_QUEUE_NOT_INITIALIZED;

public class CLIServiceAgent {

    public static final String PERSISTENT_LOCAL_DEPLOYMENTS = "LocalDeployments";
    public static final String LOCAL_DEPLOYMENT_RESOURCE = "LocalDeployment";
    private static ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Kernel kernel;

    private final LinkedBlockingQueue<Deployment> deploymentsQueue;

    private static Logger logger = LogManager.getLogger(CLIServiceAgent.class);

    @Inject
    public CLIServiceAgent(Kernel kernel, @Named(DEPLOYMENTS_QUEUE) LinkedBlockingQueue<Deployment> deploymentsQueue) {
        this.kernel = kernel;
        this.deploymentsQueue = deploymentsQueue;
    }

    /**
     * Get the details of a component with the given name.
     * @param request {@link GetComponentDetailsRequest}
     * @return {@link GetComponentDetailsResponse}
     * @throws InvalidArgumentsError thrown when empty component name is received
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
            logger.atError().kv("ComponentName", componentName)
                    .setCause(e)
                    .log("Did not find the component with the given name in Greengrass");
            throw new ComponentNotFoundError("Component with name " + componentName + " not found in Greengrass");
        }
        ComponentDetails componentDetails = ComponentDetails.builder().componentName(service.getName())
                .state(LifecycleState.valueOf(service.getState().toString()))
                .build();
        if (service.getServiceConfig().find(VERSION_CONFIG_KEY) != null) {
            componentDetails.setVersion(Coerce.toString(service.getServiceConfig().find(VERSION_CONFIG_KEY).getOnce()));
        }
        if (service.getServiceConfig().findInteriorChild(PARAMETERS_CONFIG_KEY) != null) {
            componentDetails.setConfiguration(service.getServiceConfig()
                    .findInteriorChild(PARAMETERS_CONFIG_KEY).toPOJO());
        }
        return GetComponentDetailsResponse.builder().componentDetails(componentDetails).build();
    }

    /**
     * Returns the list of all the components running in the Greengrass.
     * @return {@link ListComponentsResponse}
     */
    public ListComponentsResponse listComponents() {
        Collection<GreengrassService> services = kernel.orderedDependencies();
        List<ComponentDetails> listOfComponents = services.stream()
                .filter(service -> !service.getName().equals(kernel.getMain().getName()))
                .map(service -> {
            ComponentDetails componentDetails = ComponentDetails.builder().componentName(service.getName())
                    .state(LifecycleState.valueOf(service.getState().toString()))
                    .build();
            if (service.getServiceConfig().find(VERSION_CONFIG_KEY) != null) {
                componentDetails.setVersion(Coerce.toString(service.getServiceConfig()
                        .find(VERSION_CONFIG_KEY).getOnce()));
            }
            if (service.getServiceConfig().findInteriorChild(PARAMETERS_CONFIG_KEY) != null) {
                componentDetails.setConfiguration(service.getServiceConfig()
                    .findInteriorChild(PARAMETERS_CONFIG_KEY).toPOJO());
            }
            return componentDetails;
        }).collect(Collectors.toList());
        return ListComponentsResponse.builder().components(listOfComponents).build();
    }

    /**
     * Restart a component with the given name.
     * @param request {@link RestartComponentRequest}
     * @return {@link RestartComponentResponse}
     * @throws InvalidArgumentsError thrown when empty component name is received
     * @throws ComponentNotFoundError thrown when component does not exist
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public RestartComponentResponse restartComponent(RestartComponentRequest request)
            throws InvalidArgumentsError, ComponentNotFoundError {
        validateRestartComponentRequest(request);
        String componentName = request.getComponentName();
        try {
            GreengrassService service = kernel.locate(componentName);
            // TODO: Add any checks that can prevent triggering a restart. Right now they do not exist.
            // Success of this request means restart was triggered successfully
            service.requestRestart();
        } catch (ServiceLoadException e) {
            logger.atError().kv("ComponentName", componentName)
                    .setCause(e)
                    .log("Did not find the component with the given name in Greengrass");
            throw new ComponentNotFoundError("Component with name " + componentName + " not found in Greengrass");
        }
        return RestartComponentResponse.builder().requestStatus(RequestStatus.SUCCEEDED).build();
    }

    /**
     * Stop a component with the given name.
     * @param request {@link StopComponentRequest}
     * @return {@link StopComponentResponse}
     * @throws InvalidArgumentsError thrown when empty component name is received
     * @throws ComponentNotFoundError thrown when component does not exist
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public StopComponentResponse stopComponent(StopComponentRequest request)
            throws InvalidArgumentsError, ComponentNotFoundError {
        validateStopComponentRequest(request);
        String componentName = request.getComponentName();
        try {
            GreengrassService service = kernel.locate(componentName);
            // TODO: Add any checks that can prevent triggering a stop. Right now they do not exist.
            // Success of this request means stop was triggered successfully
            service.requestStop();
        } catch (ServiceLoadException e) {
            logger.atError().kv("ComponentName", componentName)
                    .setCause(e)
                    .log("Did not find the component with the given name in Greengrass");
            throw new ComponentNotFoundError("Component with name " + componentName + " not found in Greengrass");
        }
        return StopComponentResponse.builder().requestStatus(RequestStatus.SUCCEEDED).build();
    }

    /**
     * Copy the recipes and artifacts from given directory path to the kernel package store.
     * @param request {@link UpdateRecipesAndArtifactsRequest}
     * @throws InvalidArgumentsError thrown when both arguments are empty
     * @throws InvalidRecipesDirectoryPathError thrown when the recipe directory path is invalid or kernel does not
     *         have permissions to access it.
     * @throws InvalidArtifactsDirectoryPathError thrown when the artifacts directory path is invalid or kernel does
     *         not have permissions to access it.
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public void updateRecipesAndArtifacts(UpdateRecipesAndArtifactsRequest request)
            throws InvalidArgumentsError, InvalidRecipesDirectoryPathError, InvalidArtifactsDirectoryPathError {
        validateUpdateRecipesAndArtifactsRequest(request);
        Path kernelPackageStorePath = kernel.getComponentStorePath();
        if (!StringUtils.isEmpty(request.getRecipeDirectoryPath())) {
            Path recipeDirectoryPath = Paths.get(request.getRecipeDirectoryPath());
            Path kernelRecipeDirectoryPath = kernelPackageStorePath.resolve(ComponentStore.RECIPE_DIRECTORY);
            try {
                Utils.copyFolderRecursively(recipeDirectoryPath, kernelRecipeDirectoryPath,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.atError().setCause(e).kv("Recipe Directory path", recipeDirectoryPath)
                        .log("Caught exception while updating the recipes");
                throw new InvalidRecipesDirectoryPathError(e.getMessage());
            }
        }
        if (!StringUtils.isEmpty(request.getArtifactDirectoryPath())) {
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

    /**
     * Creates a local deployment.
     * @param serviceConfig Service config for CLI
     * @param request {@link CreateLocalDeploymentRequest}
     * @return {@link CreateLocalDeploymentResponse}
     * @throws ServiceError thrown when deployment cannot be queued
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public CreateLocalDeploymentResponse createLocalDeployment(Topics serviceConfig,
                                                               CreateLocalDeploymentRequest request)
            throws ServiceError {
        //All inputs are valid. If all inputs are empty, then user might just want to retrigger the deployment with new
        // recipes set using the updateRecipesAndArtifacts API.
        String deploymentId = UUID.randomUUID().toString();

        LocalOverrideRequest localOverrideRequest = LocalOverrideRequest.builder().requestId(deploymentId)
                .componentsToMerge(request.getRootComponentVersionsToAdd())
                .componentsToRemove(request.getRootComponentsToRemove())
                .requestTimestamp(System.currentTimeMillis())
                .groupName(request.getGroupName() == null || request.getGroupName()
                        .isEmpty() ? DEFAULT_GROUP_NAME : request.getGroupName())
                .componentNameToConfig(request.getComponentToConfiguration()).build();
        String deploymentDocument;
        try {
             deploymentDocument = OBJECT_MAPPER.writeValueAsString(localOverrideRequest);
        } catch (JsonProcessingException e) {
            logger.atError().setCause(e).log("Caught exception while parsing local deployment request");
            throw new ServiceError(e.getMessage());
        }
        Deployment deployment = new Deployment(deploymentDocument, Deployment.DeploymentType.LOCAL, deploymentId);
        if (deploymentsQueue == null) {
            logger.atError().log("Deployments queue not initialized");
            throw new ServiceError(DEPLOYMENTS_QUEUE_NOT_INITIALIZED);
        } else {
            // save the deployment status as queued
            LocalDeploymentDetails localDeploymentDetails = new LocalDeploymentDetails();
            localDeploymentDetails.setDeploymentId(deploymentId);
            localDeploymentDetails.setDeploymentType(Deployment.DeploymentType.LOCAL);
            localDeploymentDetails.setStatus(DeploymentStatus.QUEUED);
            persistLocalDeployment(serviceConfig, localDeploymentDetails.convertToMapOfObject());
            if (deploymentsQueue.offer(deployment)) {
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
     * @param request {@link GetLocalDeploymentStatusRequest}
     * @param serviceConfig Cli service configuration
     * @return {@link GetLocalDeploymentStatusResponse}
     * @throws InvalidArgumentsError thrown when invalid deploymentId format is received
     * @throws ResourceNotFoundError thrown when deployment with given Id not found
     */
    public GetLocalDeploymentStatusResponse getLocalDeploymentStatus(Topics serviceConfig,
                                                                     GetLocalDeploymentStatusRequest request)
            throws InvalidArgumentsError, ResourceNotFoundError {
        validateGetLocalDeploymentStatusRequest(request);
        Topics localDeployments = serviceConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS);
        if (localDeployments == null || localDeployments.find(request.getDeploymentId(),
                PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS) == null) {
            throw new ResourceNotFoundError("Cannot find deployment", LOCAL_DEPLOYMENT_RESOURCE,
                    request.getDeploymentId());
        } else {
            Topics deployment = localDeployments.findTopics(request.getDeploymentId());
            DeploymentStatus status =
                    Coerce.toEnum(DeploymentStatus.class,
                            deployment.find(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS));
            return GetLocalDeploymentStatusResponse.builder()
                    .deployment(LocalDeployment.builder().deploymentId(request.getDeploymentId())
                            .status(status).build()).build();
        }
    }

    /**
     * Lists last 5 local deployments.
     * @param serviceConfig CLI service configuration
     * @return {@link ListLocalDeploymentResponse}
     */
    public ListLocalDeploymentResponse listLocalDeployments(Topics serviceConfig) {
        List<LocalDeployment> persistedDeployments = new ArrayList<>();
        Topics localDeployments = serviceConfig.findTopics(PERSISTENT_LOCAL_DEPLOYMENTS);
        localDeployments.forEach(topics -> {
            Topics deploymentDetails = (Topics) topics;
            persistedDeployments.add(LocalDeployment.builder()
                    .deploymentId(Coerce.toString(deploymentDetails.find(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID)))
                    .status(Coerce.toEnum(DeploymentStatus.class, deploymentDetails.find(
                            PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS)))
                    .build());
        });
        return ListLocalDeploymentResponse.builder().localDeployments(persistedDeployments).build();
    }

    /**
     * Persists the local deployment details in the config.
     * @param serviceConfig CLI service configuration
     * @param deploymentDetails Details of the local deployment to save
     */
    public void persistLocalDeployment(Topics serviceConfig, Map<String, Object> deploymentDetails) {
        Topics localDeployments = serviceConfig.lookupTopics(PERSISTENT_LOCAL_DEPLOYMENTS);
        String deploymentId = (String) deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID);
        Topics localDeploymentDetails = localDeployments.lookupTopics(deploymentId);
        localDeploymentDetails.replaceAndWait(deploymentDetails);
        // TODO: Remove the succeeded deployments if the number of deployments have exceeded max limit
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
        @JsonProperty(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID)
        private String deploymentId;
        @JsonProperty(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS)
        private DeploymentStatus status;
        @JsonProperty(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE)
        private Deployment.DeploymentType deploymentType;

        /**
         *  Returns a map of string to object representing the deployment details.
         * @return Map of string to object
         */
        public Map<String, Object> convertToMapOfObject() {
            Map<String,Object> deploymentDetails = new HashMap<>();
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID, deploymentId);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS, status);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE, deploymentType);
            return deploymentDetails;
        }
    }
}
