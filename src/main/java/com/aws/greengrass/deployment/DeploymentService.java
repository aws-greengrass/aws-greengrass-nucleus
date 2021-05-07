/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;


import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.greengrass.deployment.exceptions.InvalidRequestException;
import com.aws.greengrass.deployment.exceptions.MissingRequiredCapabilitiesException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentResult.DeploymentStatus;
import com.aws.greengrass.deployment.model.DeploymentTask;
import com.aws.greengrass.deployment.model.DeploymentTaskMetadata;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.lifecyclemanager.UpdateSystemPolicyService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializerJson;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DefaultDeploymentTask.DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.deployment.model.DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED;

@ImplementsService(name = DeploymentService.DEPLOYMENT_SERVICE_TOPICS, autostart = true)
public class DeploymentService extends GreengrassService {

    public static final String DEPLOYMENT_SERVICE_TOPICS = "DeploymentService";
    public static final String GROUP_TO_ROOT_COMPONENTS_TOPICS = "GroupToRootComponents";
    public static final String GROUP_MEMBERSHIP_TOPICS = "GroupMembership";
    public static final String COMPONENTS_TO_GROUPS_TOPICS = "ComponentToGroups";
    public static final String GROUP_TO_ROOT_COMPONENTS_VERSION_KEY = "version";
    public static final String GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN = "groupConfigArn";
    public static final String GROUP_TO_ROOT_COMPONENTS_GROUP_NAME = "groupConfigName";
    public static final String DEPLOYMENT_DETAILED_STATUS_KEY = "detailed-deployment-status";
    public static final String DEPLOYMENT_FAILURE_CAUSE_KEY = "deployment-failure-cause";

    private static final String DEPLOYMENT_ID_LOG_KEY_NAME = "DeploymentId";
    @Getter
    private final AtomicBoolean receivedShutdown = new AtomicBoolean(false);
    private final AtomicLong pollingFrequency = new AtomicLong();
    @Inject
    DeviceConfiguration deviceConfiguration;
    @Inject
    @Setter
    private ExecutorService executorService;
    @Inject
    private DependencyResolver dependencyResolver;
    @Inject
    private ComponentManager componentManager;
    @Inject
    private KernelConfigResolver kernelConfigResolver;
    @Inject
    private DeploymentConfigMerger deploymentConfigMerger;
    @Inject
    private DeploymentDirectoryManager deploymentDirectoryManager;
    @Inject
    private DeploymentStatusKeeper deploymentStatusKeeper;
    @Inject
    private Kernel kernel;
    private DeploymentTaskMetadata currentDeploymentTaskMetadata = null;
    @Inject
    private DeploymentQueue deploymentQueue;

    @Inject
    private ComponentStore componentStore;

    @Inject
    private ThingGroupHelper thingGroupHelper;

    /**
     * Constructor.
     *
     * @param topics the configuration coming from kernel
     */
    public DeploymentService(Topics topics) {
        super(topics);
    }

    /**
     * Constructor for unit testing.
     *
     * @param topics                 The configuration coming from  kernel
     * @param executorService        Executor service coming from kernel
     * @param dependencyResolver     {@link DependencyResolver}
     * @param componentManager       {@link ComponentManager}
     * @param kernelConfigResolver   {@link KernelConfigResolver}
     * @param deploymentConfigMerger {@link DeploymentConfigMerger}
     * @param kernel                 {@link Kernel}
     * @param deviceConfiguration    {@link DeviceConfiguration}
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    DeploymentService(Topics topics, ExecutorService executorService, DependencyResolver dependencyResolver,
            ComponentManager componentManager, KernelConfigResolver kernelConfigResolver,
            DeploymentConfigMerger deploymentConfigMerger, DeploymentStatusKeeper deploymentStatusKeeper,
            DeploymentDirectoryManager deploymentDirectoryManager, Context context, Kernel kernel,
            DeviceConfiguration deviceConfiguration, ThingGroupHelper thingGroupHelper) {
        super(topics);
        this.executorService = executorService;
        this.dependencyResolver = dependencyResolver;
        this.componentManager = componentManager;
        this.kernelConfigResolver = kernelConfigResolver;
        this.deploymentConfigMerger = deploymentConfigMerger;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.deploymentDirectoryManager = deploymentDirectoryManager;
        this.context = context;
        this.kernel = kernel;
        this.deviceConfiguration = deviceConfiguration;
        this.pollingFrequency.set(getPollingFrequency(deviceConfiguration.getDeploymentPollingFrequencySeconds()));
        this.thingGroupHelper = thingGroupHelper;
    }

    @Override
    public void postInject() {
        super.postInject();
        deploymentStatusKeeper.setDeploymentService(this);
        // Informing kernel about IotJobsHelper and ShadowDeploymentListener,
        // so kernel can instantiate, inject dependencies and call post inject.
        // This is required because both the classes are independent and not Greengrass services
        context.get(IotJobsHelper.class);
        context.get(ShadowDeploymentListener.class);
        subscribeToPollingFrequencyAndGet();
    }

    @Override
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    protected void startup() throws InterruptedException {
        logger.info("Starting up the Deployment Service");
        // Reset shutdown signal since we're trying to startup here
        this.receivedShutdown.set(false);

        reportState(State.RUNNING);
        logger.info("Running deployment service");

        while (!receivedShutdown.get()) {
            if (currentDeploymentTaskMetadata != null && currentDeploymentTaskMetadata.getDeploymentResultFuture()
                    .isDone()) {
                finishCurrentDeployment();
            }
            //Cannot wait on queue because need to listen to queue as well as the currentProcessStatus future.
            //One thread cannot wait on both. If we want to make this completely event driven then we need to put
            // the waiting on currentProcessStatus in its own thread. I currently choose to not do this.
            Deployment deployment = deploymentQueue.peek();
            if (deployment != null) {
                if (deployment.isCancelled()) {
                    // Handle IoT Jobs cancellation
                    deploymentQueue.remove();

                    if (currentDeploymentTaskMetadata != null && currentDeploymentTaskMetadata.getDeploymentType()
                            .equals(deployment.getDeploymentType()) && currentDeploymentTaskMetadata.isCancellable()) {
                        // Cancel the current deployment if it's an IoT Jobs deployment
                        // that is in progress and still cancellable.
                        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                                .log("Canceling current deployment");
                        // Send interrupt signal to the deployment task.
                        cancelCurrentDeployment();
                    } else if (currentDeploymentTaskMetadata != null && !currentDeploymentTaskMetadata
                            .isCancellable()) {
                        // Ignore the cancelling signal if the deployment is NOT cancellable any more.
                        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                                .log("The current deployment cannot be cancelled");
                    }
                } else if (DeploymentType.SHADOW.equals(deployment.getDeploymentType())) {
                    // The deployment type is shadow
                    if (currentDeploymentTaskMetadata != null && DeploymentType.SHADOW
                            .equals(currentDeploymentTaskMetadata.getDeploymentType())) {
                        // A new device deployment invalidates the previous deployment, cancel the ongoing device
                        //deployment and wait till the new device deployment can be picked up.
                        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                                .log("Canceling current device deployment");
                        cancelCurrentDeployment();
                    } else if (currentDeploymentTaskMetadata == null) {
                        // Since no in progress deployment, just create a deployment.
                        deploymentQueue.remove();
                        createNewDeployment(deployment);
                    }
                } else if (DeploymentType.IOT_JOBS.equals(deployment.getDeploymentType())) {
                    // The deployment type is IoT Jobs
                    if (currentDeploymentTaskMetadata != null && currentDeploymentTaskMetadata.getDeploymentId()
                            .equals(deployment.getId()) && currentDeploymentTaskMetadata.getDeploymentType()
                            .equals(deployment.getDeploymentType())) {
                        // The new deployment is duplicate of current in progress deployment. Ignore the new one.
                        deploymentQueue.remove();
                        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, deployment.getId())
                                .log("Skip the duplicated IoT Jobs deployment");
                    } else if (currentDeploymentTaskMetadata == null) {
                        // Since no in progress deployment, just create a new deployment.
                        deploymentQueue.remove();
                        createNewDeployment(deployment);
                    }
                } else if (DeploymentType.LOCAL.equals(deployment.getDeploymentType())) {
                    // The deployment type is local
                    if (currentDeploymentTaskMetadata == null) {
                        // Since no in progress deployment, just create a new deployment.
                        deploymentQueue.remove();
                        createNewDeployment(deployment);
                    }
                } else {
                    logger.atError().kv(DEPLOYMENT_ID_LOG_KEY_NAME, deployment.getId())
                            .kv("DeploymentType", deployment.getDeploymentType()).log("Unknown deployment type");
                }
            }
            Thread.sleep(pollingFrequency.get());
        }
    }

    private void subscribeToPollingFrequencyAndGet() {
        deviceConfiguration.getDeploymentPollingFrequencySeconds()
                .subscribe((whatHappened, frequency) -> pollingFrequency.set(getPollingFrequency(frequency)));
    }

    private Long getPollingFrequency(Topic pollingFrequencyTopic) {
        return Duration.ofSeconds(Coerce.toLong(pollingFrequencyTopic)).toMillis();
    }

    @Override
    protected void shutdown() {
        receivedShutdown.set(true);
        IotJobsHelper iotJobsHelper = context.get(IotJobsHelper.class);
        if (iotJobsHelper != null) {
            iotJobsHelper.unsubscribeFromIotJobsTopics();
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void finishCurrentDeployment() throws InterruptedException {
        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                .log("Current deployment finished");
        try {
            // No timeout is set here. Detection of error is delegated to downstream components like
            // dependency resolver, package downloader, kernel which will have more visibility
            // if something is going wrong
            DeploymentResult result = currentDeploymentTaskMetadata.getDeploymentResultFuture().get();
            if (result != null) {
                DeploymentStatus deploymentStatus = result.getDeploymentStatus();

                Map<String, String> statusDetails = new HashMap<>();
                statusDetails.put(DEPLOYMENT_DETAILED_STATUS_KEY, deploymentStatus.name());
                if (DeploymentStatus.SUCCESSFUL.equals(deploymentStatus)) {
                    //Add the root packages of successful deployment to the configuration
                    persistGroupToRootComponents(currentDeploymentTaskMetadata.getDeploymentDocument());

                    deploymentStatusKeeper
                            .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                                               currentDeploymentTaskMetadata.getDeploymentType(),
                                                               JobStatus.SUCCEEDED.toString(), statusDetails);

                    if (currentDeploymentTaskMetadata.getDeploymentTask() instanceof KernelUpdateDeploymentTask) {
                        try {
                            kernel.getContext().get(KernelAlternatives.class).activationSucceeds();
                        } catch (IOException e) {
                            logger.atError().log("Failed to reset Nucleus activate directory", e);
                        }
                    }
                    deploymentDirectoryManager.persistLastSuccessfulDeployment();
                } else {
                    if (result.getFailureCause() != null) {
                        statusDetails.put(DEPLOYMENT_FAILURE_CAUSE_KEY, result.getFailureCause().getMessage());
                    }
                    if (FAILED_ROLLBACK_NOT_REQUESTED.equals(result.getDeploymentStatus())) {
                        // Update the groupToRootComponents mapping in config for the case where there is no rollback
                        // and now the components deployed for the current group are not the same as before deployment
                        persistGroupToRootComponents(currentDeploymentTaskMetadata.getDeploymentDocument());
                    }
                    deploymentStatusKeeper
                            .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                                               currentDeploymentTaskMetadata.getDeploymentType(),
                                                               JobStatus.FAILED.toString(), statusDetails);

                    if (currentDeploymentTaskMetadata.getDeploymentTask() instanceof KernelUpdateDeploymentTask) {
                        try {
                            kernel.getContext().get(KernelAlternatives.class).rollbackCompletes();
                        } catch (IOException e) {
                            logger.atError().log("Failed to reset Nucleus rollback directory", e);
                        }
                    }
                    deploymentDirectoryManager.persistLastFailedDeployment();
                }
            }
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof InterruptedException) {
                logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                        .log("Deployment task is interrupted");
            } else {
                // This code path can only occur when DeploymentTask throws unchecked exception.
                logger.atError().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                        .setCause(t).log("Deployment task throws unknown exception");
                HashMap<String, String> statusDetails = new HashMap<>();
                statusDetails.put("error", t.getMessage());
                deploymentStatusKeeper
                        .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                currentDeploymentTaskMetadata.getDeploymentType(), JobStatus.FAILED.toString(),
                                statusDetails);
                deploymentDirectoryManager.persistLastFailedDeployment();
            }
        } catch (CancellationException e) {
            logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                    .log("Deployment task is cancelled");
        }
        // Setting this to null to indicate there is not current deployment being processed
        // Did not use optionals over null due to performance
        currentDeploymentTaskMetadata = null;
    }

    private void persistGroupToRootComponents(DeploymentDocument deploymentDocument) {
        Map<String, Object> deploymentGroupToRootPackages = new HashMap<>();
        Topics deploymentGroupTopics = config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS);
        Topics groupMembershipTopics = config.lookupTopics(GROUP_MEMBERSHIP_TOPICS);
        deploymentGroupTopics.forEach(node -> {
            Topics groupTopics = (Topics) node;
            if (groupMembershipTopics.find(groupTopics.getName()) == null
                    && !groupTopics.getName().startsWith(DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX)) {
                groupTopics.remove();
            }
        });
        groupMembershipTopics.remove();
        deploymentDocument.getDeploymentPackageConfigurationList().stream().forEach(pkgConfig -> {
            if (pkgConfig.isRootComponent()) {
                Map<String, Object> pkgDetails = new HashMap<>();
                pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, pkgConfig.getResolvedVersion());
                pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN,
                        deploymentDocument.getDeploymentId());
                pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_NAME, deploymentDocument.getGroupName());
                deploymentGroupToRootPackages.put(pkgConfig.getPackageName(), pkgDetails);
            }
        });
        deploymentGroupTopics.lookupTopics(deploymentDocument.getGroupName())
                .replaceAndWait(deploymentGroupToRootPackages);
        setComponentsToGroupsMapping(deploymentGroupTopics);
    }

    /*
     * When a cancellation is received, there are following possibilities -
     *  - No task has yet been created for current deployment so result future is null, we do nothing for this
     *  - If the result future is already cancelled, nothing to do.
     *  - If the result future has already completed then we cannot cancel it, we do nothing for this
     *  - The deployment is not yet running the update, i.e. it may be in any one of dependency resolution stage/
     *    package download stage/ config resolution stage/ waiting for safe time to update as part of the merge stage,
     *    in that case we cancel that update and the DeploymentResult future
     *  - The deployment is already executing the update, so we let it finish
     * For cases when deployment cannot be cancelled customers can figure out what happened through logs
     * because in the case of IoT jobs, a cancelled job does not accept status update
     */
    @SuppressWarnings("PMD.NullAssignment")
    private void cancelCurrentDeployment() {
        if (currentDeploymentTaskMetadata.getDeploymentResultFuture() != null && !currentDeploymentTaskMetadata
                .getDeploymentResultFuture().isCancelled()) {
            if (currentDeploymentTaskMetadata.getDeploymentResultFuture().isDone() || !currentDeploymentTaskMetadata
                    .isCancellable()) {
                logger.atInfo().log("Deployment already finished processing or cannot be cancelled");
            } else {
                boolean canCancelDeployment = context.get(UpdateSystemPolicyService.class).discardPendingUpdateAction(
                        ((DefaultDeploymentTask) currentDeploymentTaskMetadata.getDeploymentTask()).getDeployment()
                                .getDeploymentDocumentObj().getDeploymentId());
                if (canCancelDeployment) {
                    currentDeploymentTaskMetadata.getDeploymentResultFuture().cancel(true);
                    if (DeploymentType.SHADOW.equals(currentDeploymentTaskMetadata.getDeploymentType())) {
                        deploymentStatusKeeper
                                .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                        currentDeploymentTaskMetadata.getDeploymentType(),
                                        JobStatus.CANCELED.toString(), new HashMap<>());
                    }
                    logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                            .log("Deployment was cancelled");
                } else {
                    logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                            .log("Deployment is in a stage where it cannot be cancelled,"
                                         + " need to wait for it to finish");
                }
            }
        }
    }

    private void createNewDeployment(Deployment deployment) {
        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, deployment.getId())
                .kv("DeploymentType", deployment.getDeploymentType().toString())
                .log("Received deployment in the queue");

        DeploymentTask deploymentTask;
        boolean cancellable = true;
        if (DEFAULT.equals(deployment.getDeploymentStage())) {
            deploymentTask = createDefaultNewDeployment(deployment);
        } else {
            deploymentTask = createKernelUpdateDeployment(deployment);
            cancellable = false;
            if (DeploymentType.IOT_JOBS.equals(deployment.getDeploymentType())) {
                // Keep track of IoT jobs for de-duplication
                IotJobsHelper.getLatestQueuedJobs().addProcessedJob(deployment.getId());
            }
        }
        if (deploymentTask == null) {
            return;
        }
        deploymentStatusKeeper.persistAndPublishDeploymentStatus(deployment.getId(), deployment.getDeploymentType(),
                                                                 JobStatus.IN_PROGRESS.toString(), new HashMap<>());

        if (DEFAULT.equals(deployment.getDeploymentStage())) {

            try {
                context.get(KernelAlternatives.class).cleanupLaunchDirectoryLinks();
                deploymentDirectoryManager.createNewDeploymentDirectory(deployment.getDeploymentDocumentObj()
                        .getDeploymentId());
                deploymentDirectoryManager.writeDeploymentMetadata(deployment);
            } catch (IOException ioException) {
                logger.atError().log("Unable to create deployment directory", ioException);
                updateDeploymentResultAsFailed(deployment, deploymentTask, true,
                        new DeploymentTaskFailureException(ioException));
                return;
            }

            List<String> requiredCapabilities = deployment.getDeploymentDocumentObj().getRequiredCapabilities();
            if (requiredCapabilities != null && !requiredCapabilities.isEmpty()) {
                List<String> missingCapabilities = requiredCapabilities.stream()
                        .filter(reqCapabilities -> !kernel.getSupportedCapabilities().contains(reqCapabilities))
                        .collect(Collectors.toList());
                if (!missingCapabilities.isEmpty()) {
                    updateDeploymentResultAsFailed(deployment, deploymentTask, false,
                            new MissingRequiredCapabilitiesException("The current nucleus version doesn't support one "
                                    + "or more capabilities that are required by this deployment: "
                                    + String.join(", ", missingCapabilities)));
                    return;
                }
            }

            if (DeploymentType.LOCAL.equals(deployment.getDeploymentType())) {
                try {
                    copyRecipesAndArtifacts(deployment);
                } catch (InvalidRequestException | IOException e) {
                    logger.atError().log("Error copying recipes and artifacts", e);
                    updateDeploymentResultAsFailed(deployment, deploymentTask, false, e);
                    return;
                }
            }
        }


        Future<DeploymentResult> process = executorService.submit(deploymentTask);
        logger.atInfo().kv("deployment", deployment.getId()).log("Started deployment execution");

        currentDeploymentTaskMetadata =
                new DeploymentTaskMetadata(deploymentTask, process, deployment.getId(), deployment.getDeploymentType(),
                                           new AtomicInteger(1), deployment.getDeploymentDocumentObj(), cancellable);
    }

    private void updateDeploymentResultAsFailed(Deployment deployment, DeploymentTask deploymentTask,
                                                boolean completeExceptionally, Exception e) {
        DeploymentResult result = new DeploymentResult(DeploymentStatus.FAILED_NO_STATE_CHANGE, e);
        CompletableFuture<DeploymentResult> process;
        if (completeExceptionally) {
            process = new CompletableFuture<>();
            process.completeExceptionally(e);
        } else {
            process = CompletableFuture.completedFuture(result);
        }
        currentDeploymentTaskMetadata = new DeploymentTaskMetadata(deploymentTask, process, deployment.getId(),
                deployment.getDeploymentType(), new AtomicInteger(1),
                deployment.getDeploymentDocumentObj(), false);
    }

    @SuppressWarnings("PMD.ExceptionAsFlowControl")
    private void copyRecipesAndArtifacts(Deployment deployment) throws InvalidRequestException, IOException {
        try {
            LocalOverrideRequest localOverrideRequest = SerializerFactory.getFailSafeJsonObjectMapper()
                    .readValue(deployment.getDeploymentDocument(), LocalOverrideRequest.class);
            if (!Utils.isEmpty(localOverrideRequest.getRecipeDirectoryPath())) {
                Path recipeDirectoryPath = Paths.get(localOverrideRequest.getRecipeDirectoryPath());
                copyRecipesToComponentStore(recipeDirectoryPath);

            }

            if (!Utils.isEmpty(localOverrideRequest.getArtifactsDirectoryPath())) {
                Path kernelArtifactsDirectoryPath = kernel.getNucleusPaths().componentStorePath()
                        .resolve(ComponentStore.ARTIFACT_DIRECTORY);
                Path artifactsDirectoryPath = Paths.get(localOverrideRequest.getArtifactsDirectoryPath());
                try {
                    Utils.copyFolderRecursively(artifactsDirectoryPath, kernelArtifactsDirectoryPath,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IOException(String.format("Unable to copy artifacts from  %s due to: %s",
                            artifactsDirectoryPath.toString(), e.getMessage()), e);
                }
            }
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Unable to parse the deployment request - Invalid JSON", e);
        }
    }

    private void copyRecipesToComponentStore(Path from) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    copyRecipeFileToComponentStore(componentStore, r, logger);
                }
            }
        }
    }

    /**
     * Copy the given recipe file to local component store.
     *
     * @param componentStore ComponentStore instance
     * @param recipePath path to the recipe file
     * @param logger Logger instance
     * @return ComponentRecipe file content
     * @throws IOException on I/O error
     */
    @SuppressWarnings("PMD.ExceptionAsFlowControl")
    public static ComponentRecipe copyRecipeFileToComponentStore(ComponentStore componentStore,
                                                                 Path recipePath, Logger logger) throws IOException {
        String ext = Utils.extension(recipePath.toString());
        ComponentRecipe recipe = null;

        //reading it in as a recipe, so that will fail if it is malformed with a good error.
        //The second reason to do this is to parse the name and version so that we can properly name
        //the file when writing it into the local recipe store.
        try {
            if (recipePath.toFile().length() > 0) {
                switch (ext.toLowerCase()) {
                    case "yaml":
                    case "yml":
                        recipe = getRecipeSerializer().readValue(recipePath.toFile(), ComponentRecipe.class);
                        break;
                    case "json":
                        recipe = getRecipeSerializerJson().readValue(recipePath.toFile(), ComponentRecipe.class);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            // Throw on error so that the user will receive this message and we will stop the deployment.
            // This is to fail fast while providing actionable feedback.
            throw new IOException(
                    String.format("Unable to parse %s as a recipe due to: %s", recipePath.toString(), e.getMessage()),
                    e);
        }
        if (recipe == null) {
            logger.atError().log("Skipping file {} because it was not recognized as a recipe", recipePath);
            return null;
        }

        // Write the recipe as YAML with the proper filename into the store
        ComponentIdentifier componentIdentifier =
                new ComponentIdentifier(recipe.getComponentName(), recipe.getComponentVersion());

        try {
            componentStore
                    .savePackageRecipe(componentIdentifier, getRecipeSerializer().writeValueAsString(recipe));
        } catch (PackageLoadingException e) {
            // Throw on error so that the user will receive this message and we will stop the deployment.
            // This is to fail fast while providing actionable feedback.
            throw new IOException(String.format("Unable to copy recipe for '%s' to component store due to: %s",
                    componentIdentifier.toString(), e.getMessage()), e);
        }
        return recipe;
    }

    private KernelUpdateDeploymentTask createKernelUpdateDeployment(Deployment deployment) {
        return new KernelUpdateDeploymentTask(kernel, logger.createChild(), deployment, componentManager);
    }

    private DefaultDeploymentTask createDefaultNewDeployment(Deployment deployment) {
        try {
            logger.atInfo().kv("document", deployment.getDeploymentDocument())
                    .log("Received deployment document in queue");
            parseAndValidateJobDocument(deployment);
        } catch (InvalidRequestException e) {
            logger.atError().cause(e).kv(DEPLOYMENT_ID_LOG_KEY_NAME, deployment.getId())
                    .kv("DeploymentType", deployment.getDeploymentType().toString())
                    .log("Invalid document for deployment");
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", e.getMessage());
            deploymentStatusKeeper
                    .persistAndPublishDeploymentStatus(deployment.getId(), deployment.getDeploymentType(),
                            JobStatus.FAILED.toString(), statusDetails);
            return null;
        }
        return new DefaultDeploymentTask(dependencyResolver, componentManager, kernelConfigResolver,
                deploymentConfigMerger, logger.createChild(),
                deployment, config, executorService, thingGroupHelper);
    }

    private DeploymentDocument parseAndValidateJobDocument(Deployment deployment) throws InvalidRequestException {
        String jobDocumentString = deployment.getDeploymentDocument();
        if (Utils.isEmpty(jobDocumentString)) {
            throw new InvalidRequestException("Job document cannot be empty");
        }
        DeploymentDocument document;
        try {
            switch (deployment.getDeploymentType()) {
                case LOCAL:
                    LocalOverrideRequest localOverrideRequest = SerializerFactory.getFailSafeJsonObjectMapper()
                            .readValue(jobDocumentString, LocalOverrideRequest.class);
                    Map<String, String> rootComponents = new HashMap<>();
                    Set<String> rootComponentsInRequestedGroup = new HashSet<>();
                    config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS,
                                        localOverrideRequest.getGroupName() == null ? LOCAL_DEPLOYMENT_GROUP_NAME
                                                : localOverrideRequest.getGroupName())
                            .forEach(t -> rootComponentsInRequestedGroup.add(t.getName()));
                    if (!Utils.isEmpty(rootComponentsInRequestedGroup)) {
                        rootComponentsInRequestedGroup.forEach(c -> {
                            Topics serviceTopic = kernel.findServiceTopic(c);
                            if (serviceTopic != null) {
                                String version = Coerce.toString(serviceTopic.find(VERSION_CONFIG_KEY));
                                rootComponents.put(c, version);
                            }
                        });
                    }
                    document = DeploymentDocumentConverter
                            .convertFromLocalOverrideRequestAndRoot(localOverrideRequest, rootComponents);
                    break;
                case IOT_JOBS:
                case SHADOW:

                    // Note: This is the data contract that gets sending down from FCS::CreateDeployment
                    // Configuration is really a bad name choice as it is too generic but we can change it later
                    // since it is only a internal model
                    Configuration configuration = SerializerFactory.getFailSafeJsonObjectMapper()
                            .readValue(jobDocumentString, Configuration.class);
                    document = DeploymentDocumentConverter.convertFromDeploymentConfiguration(configuration);

                    break;
                default:
                    throw new IllegalArgumentException("Invalid deployment type: " + deployment.getDeploymentType());
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidRequestException("Unable to parse the job document", e);
        }
        deployment.setDeploymentDocumentObj(document);
        return document;
    }

    void setDeploymentsQueue(DeploymentQueue deploymentQueue) {
        this.deploymentQueue = deploymentQueue;
    }

    public DeploymentTaskMetadata getCurrentDeploymentTaskMetadata() {
        return currentDeploymentTaskMetadata;
    }

    void setComponentsToGroupsMapping(Topics groupsToRootComponents) {
        Set<String> pendingComponents = new HashSet<>();
        Map<String, Object> componentsToGroupsMappingCache = new ConcurrentHashMap<>();
        Topics componentsToGroupsTopics = getConfig().lookupTopics(COMPONENTS_TO_GROUPS_TOPICS);
        /*
         * Structure of COMPONENTS_TO_GROUPS_TOPICS is:
         * COMPONENTS_TO_GROUPS_TOPICS :
         * |_ <componentName> :
         *     |_ <deploymentID> : <GroupName>
         * This stores all the components with the list of deployment IDs associated to it along with the thing group
         * (if available) to be associated to the deployment.
         */
        // Get all the groups associated to the root components.
        groupsToRootComponents.forEach(groupNode -> ((Topics) groupNode).forEach(componentNode -> {
            Topics componentTopics = (Topics) componentNode;

            Topic groupConfigTopic = componentTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN);
            String groupConfig = Coerce.toString(groupConfigTopic);

            Topic groupNameTopic = componentTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_NAME);
            String groupName = Coerce.toString(groupNameTopic);

            Map<String, Object> groupDeploymentIdSet = (Map<String, Object>) componentsToGroupsMappingCache
                    .getOrDefault(componentTopics.getName(), new HashMap<>());
            groupDeploymentIdSet.putIfAbsent(groupConfig, groupName);
            componentsToGroupsMappingCache.put(componentTopics.getName(), groupDeploymentIdSet);
            pendingComponents.add(componentTopics.getName());
        }));

        // Associate the groups to the dependant services based on the services it is depending on.
        while (!pendingComponents.isEmpty()) {
            String componentName = pendingComponents.iterator().next();
            try {
                GreengrassService greengrassService = kernel.locate(componentName);
                Map<String, Object> groupNamesForComponent = (Map<String, Object>) componentsToGroupsMappingCache
                        .getOrDefault(greengrassService.getName(), new HashMap<>());

                greengrassService.getDependencies().forEach((greengrassService1, dependencyType) -> {
                    pendingComponents.add(greengrassService1.getName());
                    Map<String, Object> groupNamesForDependentComponent =
                            (Map<String, Object>) componentsToGroupsMappingCache
                                    .getOrDefault(greengrassService1.getName(), new HashMap<>());
                    groupNamesForDependentComponent.putAll(groupNamesForComponent);
                    componentsToGroupsMappingCache.put(greengrassService1.getName(), groupNamesForDependentComponent);
                });
            } catch (ServiceLoadException ex) {
                logger.atError().cause(ex).log("Unable to get status for {}.", componentName);
            }
            pendingComponents.remove(componentName);
        }

        if (componentsToGroupsTopics != null) {
            componentsToGroupsTopics.replaceAndWait(componentsToGroupsMappingCache);
        }
    }

    /**
     * Gets the list of all the groups that the component is a part of. This method is used by log manager.
     *
     * @param componentName The name of the component.
     * @return The list of groups the component is a part of.
     */
    public Set<String> getGroupNamesForUserComponent(String componentName) {
        Topics componentsToGroupsTopics = config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS);

        Set<String> componentGroups = new HashSet<>();
        if (componentsToGroupsTopics != null) {
            Topics groupsTopics = componentsToGroupsTopics.lookupTopics(componentName);
            groupsTopics.children.values().stream().map(n -> (Topic) n).forEach(topic -> {
                String groupName = Coerce.toString(topic);
                if (!Utils.isEmpty(groupName)) {
                    componentGroups.add(groupName);
                }
            });
        }
        return componentGroups;
    }

    /**
     * Gets the list of all the groups that the thing is a part of. This method is used by log manager.
     *
     * @return All the group configs.
     */
    public Set<String> getAllGroupNames() {
        Topics componentsToGroupsTopics = config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS);

        Set<String> allGroupNames = new HashSet<>();
        if (componentsToGroupsTopics != null) {
            componentsToGroupsTopics.iterator().forEachRemaining(node -> {
                Topics groupsTopics = (Topics) node;
                groupsTopics.children.values().stream().map(n -> (Topic) n).forEach(topic -> {
                    String groupName = Coerce.toString(topic);
                    if (!Utils.isEmpty(groupName)) {
                        allGroupNames.add(groupName);
                    }
                });

            });
        }
        return allGroupNames;
    }

    /**
     * Checks whether a component is a root component or not.
     *
     * @param componentName The name of the component.
     * @return a boolean indicating whether a component is a root component or not.
     */
    public boolean isComponentRoot(String componentName) {
        Topics groupToRootComponentsTopics = config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS);
        if (groupToRootComponentsTopics != null) {
            for (Node node : groupToRootComponentsTopics.children.values()) {
                if (node instanceof Topics) {
                    Topics groupTopics = (Topics) node;
                    for (Node componentNode: groupTopics.children.values()) {
                        if (componentNode instanceof Topics) {
                            Topics componentTopics = (Topics) componentNode;
                            if (componentName.equals(componentTopics.getName())) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
