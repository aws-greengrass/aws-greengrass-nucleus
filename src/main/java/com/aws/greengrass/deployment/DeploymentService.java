/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.deployment;


import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.exceptions.InvalidRequestException;
import com.aws.greengrass.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.greengrass.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentTask;
import com.aws.greengrass.deployment.model.DeploymentTaskMetadata;
import com.aws.greengrass.deployment.model.FleetConfiguration;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.UpdateSystemSafelyService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.DEFAULT_GROUP_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;

@ImplementsService(name = DeploymentService.DEPLOYMENT_SERVICE_TOPICS, autostart = true)
public class DeploymentService extends GreengrassService {

    public static final String DEPLOYMENT_SERVICE_TOPICS = "DeploymentService";
    public static final String GROUP_TO_ROOT_COMPONENTS_TOPICS = "GroupToRootComponents";
    public static final String COMPONENTS_TO_GROUPS_TOPICS = "ComponentToGroups";
    public static final String LAST_SUCCESSFUL_SHADOW_DEPLOYMENT_ID_TOPIC = "LastSuccessfulShadowDeploymentId";
    public static final String GROUP_TO_ROOT_COMPONENTS_VERSION_KEY = "version";
    public static final String GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN = "groupConfigArn";
    public static final String GROUP_TO_ROOT_COMPONENTS_GROUP_NAME = "groupConfigName";

    // TODO: These should probably become configurable parameters eventually
    private static final long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(15).toMillis();
    private static final int DEPLOYMENT_MAX_ATTEMPTS = 3;
    private static final String DEPLOYMENT_ID_LOG_KEY_NAME = "DeploymentId";

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

    @Getter
    private final AtomicBoolean receivedShutdown = new AtomicBoolean(false);

    @Setter
    private long pollingFrequency = DEPLOYMENT_POLLING_FREQUENCY;

    @Inject
    private DeploymentQueue deploymentQueue;

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
     * @param componentManager         {@link ComponentManager}
     * @param kernelConfigResolver   {@link KernelConfigResolver}
     * @param deploymentConfigMerger {@link DeploymentConfigMerger}
     * @param kernel                 {@link Kernel}
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    DeploymentService(Topics topics, ExecutorService executorService, DependencyResolver dependencyResolver,
                      ComponentManager componentManager, KernelConfigResolver kernelConfigResolver,
                      DeploymentConfigMerger deploymentConfigMerger, DeploymentStatusKeeper deploymentStatusKeeper,
                      DeploymentDirectoryManager deploymentDirectoryManager, Context context, Kernel kernel) {
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
    }

    @Override
    public void postInject() {
        super.postInject();
        // Informing kernel about IotJobsHelper and ShadowDeploymentListener,
        // so kernel can instantiate, inject dependencies and call post inject.
        // This is required because both the classes are independent and not Greengrass services
        context.get(IotJobsHelper.class);
        context.get(ShadowDeploymentListener.class);
        deploymentStatusKeeper.setDeploymentService(this);
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
                if (currentDeploymentTaskMetadata != null && currentDeploymentTaskMetadata.getDeploymentType()
                        .equals(deployment.getDeploymentType()) && deployment.isCancelled()
                        && currentDeploymentTaskMetadata.isCancellable()) {
                    logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                            .log("Canceling current deployment");
                    // Assuming cancel will either cancel the current deployment or wait till it finishes
                    cancelCurrentDeployment();
                }
                if (currentDeploymentTaskMetadata != null && deployment.getId()
                        .equals(currentDeploymentTaskMetadata.getDeploymentId()) && deployment.getDeploymentType()
                        .equals(currentDeploymentTaskMetadata.getDeploymentType())) {
                    // Duplicate message and already processing this deployment so nothing is needed
                    deploymentQueue.remove();
                    continue;
                }
                if (deployment.getDeploymentType().equals(DeploymentType.SHADOW)) {
                    // A new device deployment invalidates the previous deployment, cancel the ongoing device deployment
                    // and wait till the new device deployment can be picked up.
                    if (currentDeploymentTaskMetadata != null
                            && currentDeploymentTaskMetadata.getDeploymentType().equals(DeploymentType.SHADOW)) {
                        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                                .log("Canceling current device deployment");
                        cancelCurrentDeployment();
                        continue;
                    }
                    // On device start up, Shadow listener will fetch the shadow and schedule a shadow deployment
                    // Discard the deployment if Kernel starts up from a tlog file and has already processed deployment
                    if (deployment.getId().equals(
                            Coerce.toString(config.lookup(LAST_SUCCESSFUL_SHADOW_DEPLOYMENT_ID_TOPIC)))) {
                        deploymentQueue.remove();
                        continue;
                    }
                }
                if (currentDeploymentTaskMetadata != null) {
                    // wait till the current deployment finishes
                    continue;
                }
                deploymentQueue.remove();
                if (!deployment.isCancelled()) {
                    createNewDeployment(deployment);
                }
            }
            Thread.sleep(pollingFrequency);
        }
    }

    @Override
    protected void shutdown() {
        receivedShutdown.set(true);
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
                DeploymentResult.DeploymentStatus deploymentStatus = result.getDeploymentStatus();

                Map<String, String> statusDetails = new HashMap<>();
                statusDetails.put("detailed-deployment-status", deploymentStatus.name());
                if (deploymentStatus.equals(DeploymentResult.DeploymentStatus.SUCCESSFUL)) {
                    //Add the root packages of successful deployment to the configuration
                    DeploymentDocument deploymentDocument = currentDeploymentTaskMetadata.getDeploymentDocument();
                    Topics deploymentGroupTopics = config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS,
                            deploymentDocument.getGroupName());

                    if (DeploymentType.SHADOW.equals(currentDeploymentTaskMetadata.getDeploymentType())) {
                        config.lookup(LAST_SUCCESSFUL_SHADOW_DEPLOYMENT_ID_TOPIC)
                                .withValue(currentDeploymentTaskMetadata.getDeploymentId());
                    }
                    Map<String, Object> deploymentGroupToRootPackages = new HashMap<>();
                    // TODO: Removal of group from the mappings. Currently there is no action taken when a device is
                    //  removed from a thing group. Empty configuration is treated as a valid config for a group but
                    //  not treated as removal.
                    deploymentDocument.getDeploymentPackageConfigurationList().stream().forEach(pkgConfig -> {
                        if (pkgConfig.isRootComponent()) {
                            Map<String, Object> pkgDetails = new HashMap<>();
                            pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, pkgConfig.getResolvedVersion());
                            pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN,
                                    deploymentDocument.getDeploymentId());
                            pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_NAME,
                                    deploymentDocument.getGroupName());
                            deploymentGroupToRootPackages.put(pkgConfig.getPackageName(), pkgDetails);
                        }
                    });
                    deploymentGroupTopics.replaceAndWait(deploymentGroupToRootPackages);
                    setComponentsToGroupsMapping(deploymentGroupTopics);
                    deploymentStatusKeeper
                            .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                    currentDeploymentTaskMetadata.getDeploymentType(), JobStatus.SUCCEEDED.toString(),
                                    statusDetails);
                    deploymentDirectoryManager.persistLastSuccessfulDeployment();
                } else {
                    if (result.getFailureCause() != null) {
                        statusDetails.put("deployment-failure-cause", result.getFailureCause().getMessage());
                    }
                    //TODO: Update the groupToRootPackages mapping in config for the case where there is no rollback
                    // and now the packages deployed for the current group are not the same as before starting
                    // deployment
                    deploymentStatusKeeper
                            .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                    currentDeploymentTaskMetadata.getDeploymentType(), JobStatus.FAILED.toString(),
                                    statusDetails);
                    deploymentDirectoryManager.persistLastFailedDeployment();
                }
            }
        } catch (ExecutionException e) {
            logger.atError().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId()).setCause(e)
                    .log("Caught exception while getting the status of the Job");
            Throwable t = e.getCause();
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", t.getMessage());
            if (t instanceof NonRetryableDeploymentTaskFailureException
                    || currentDeploymentTaskMetadata.getDeploymentAttemptCount().get() >= DEPLOYMENT_MAX_ATTEMPTS) {
                deploymentStatusKeeper
                        .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                currentDeploymentTaskMetadata.getDeploymentType(), JobStatus.FAILED.toString(),
                                statusDetails);
                deploymentDirectoryManager.persistLastFailedDeployment();
            } else if (t instanceof RetryableDeploymentTaskFailureException) {
                // Resubmit task, increment attempt count and return
                currentDeploymentTaskMetadata.setDeploymentResultFuture(
                        executorService.submit(currentDeploymentTaskMetadata.getDeploymentTask()));
                currentDeploymentTaskMetadata.getDeploymentAttemptCount().incrementAndGet();
                return;
            }
        }
        // Setting this to null to indicate there is not current deployment being processed
        // Did not use optionals over null due to performance
        currentDeploymentTaskMetadata = null;
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
        if (currentDeploymentTaskMetadata.getDeploymentResultFuture() != null
                && !currentDeploymentTaskMetadata.getDeploymentResultFuture().isCancelled()) {
            if (currentDeploymentTaskMetadata.getDeploymentResultFuture().isDone()
                    || !currentDeploymentTaskMetadata.isCancellable()) {
                logger.atInfo().log("Deployment already finished processing or cannot be cancelled");
            } else {
                boolean canCancelDeployment = context.get(UpdateSystemSafelyService.class).discardPendingUpdateAction(
                        ((DefaultDeploymentTask) currentDeploymentTaskMetadata.getDeploymentTask()).getDeployment()
                                .getDeploymentDocumentObj().getDeploymentId());
                if (canCancelDeployment) {
                    currentDeploymentTaskMetadata.getDeploymentResultFuture().cancel(true);
                    logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                            .log("Deployment was cancelled");
                } else {
                    logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                            .log("Deployment is in a stage where it cannot be cancelled,"
                                    + "need to wait for it to finish");
                    try {
                        currentDeploymentTaskMetadata.getDeploymentResultFuture().get();
                    } catch (ExecutionException | InterruptedException e) {
                        logger.atError().kv(DEPLOYMENT_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                                .log("Error while finishing "
                                        + "deployment, no-op since the deployment was canceled at the source");
                    }
                }
            }
            // Currently cancellation for only IoT Jobs based deployments is supported and for such deployments job
            // status should not be reported back since once a job is cancelled IoT Jobs will reject any status
            // updates for it. however, if we later support cancellation for more deployment types this may need to
            // be handled on case by case basis
            currentDeploymentTaskMetadata = null;
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
        }
        if (deploymentTask == null) {
            return;
        }
        deploymentStatusKeeper.persistAndPublishDeploymentStatus(deployment.getId(), deployment.getDeploymentType(),
                JobStatus.IN_PROGRESS.toString(), new HashMap<>());
        try {
            deploymentDirectoryManager.createNewDeploymentDirectoryIfNotExists(
                    deployment.getDeploymentDocumentObj().getDeploymentId());
            deploymentDirectoryManager.writeDeploymentMetadata(deployment);
        } catch (IOException ioException) {
            logger.atError().log("Unable to create deployment directory", ioException);
        }
        Future<DeploymentResult> process = executorService.submit(deploymentTask);
        logger.atInfo().kv("deployment", deployment.getId()).log("Started deployment execution");

        currentDeploymentTaskMetadata =
                new DeploymentTaskMetadata(deploymentTask, process, deployment.getId(), deployment.getDeploymentType(),
                        new AtomicInteger(1), deployment.getDeploymentDocumentObj(), cancellable);
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
            logger.atError().kv(DEPLOYMENT_ID_LOG_KEY_NAME, deployment.getId())
                    .kv("DeploymentType", deployment.getDeploymentType().toString())
                    .log("Invalid document for deployment");
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", e.getMessage());
            deploymentStatusKeeper.persistAndPublishDeploymentStatus(deployment.getId(), deployment.getDeploymentType(),
                    JobStatus.FAILED.toString(), statusDetails);
            return null;
        }
        return new DefaultDeploymentTask(dependencyResolver, componentManager, kernelConfigResolver,
                deploymentConfigMerger, logger.createChild(), deployment, config);
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
                    LocalOverrideRequest localOverrideRequest = SerializerFactory.getJsonObjectMapper().readValue(
                            jobDocumentString, LocalOverrideRequest.class);
                    Map<String, String> rootComponents = new HashMap<>();
                    Set<String> rootComponentsInRequestedGroup = new HashSet<>();
                    config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS,
                            localOverrideRequest.getGroupName() == null ? DEFAULT_GROUP_NAME
                                    : localOverrideRequest.getGroupName())
                            .forEach(t -> rootComponentsInRequestedGroup.add(t.getName()));
                    //TODO: pulling the versions from kernel. Can pull it from the config itself.
                    // Confirm if pulling from config should not break any use case for local
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
                    FleetConfiguration config = SerializerFactory.getJsonObjectMapper()
                            .readValue(jobDocumentString, FleetConfiguration.class);
                    document = DeploymentDocumentConverter.convertFromFleetConfiguration(config);
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

    private void setComponentsToGroupsMapping(Topics groupsToRootComponents) {
        List<String> pendingComponentsList = new LinkedList<>();
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
        groupsToRootComponents.iterator().forEachRemaining(groupNode -> {
            Topics componentTopics = (Topics) groupNode;

            Topic groupConfigTopic = componentTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN);
            String groupConfig = Coerce.toString(groupConfigTopic);

            Topic groupNameTopic = componentTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_NAME);
            String groupName = Coerce.toString(groupNameTopic);

            Map<String, Object> groupDeploymentIdSet = (Map<String, Object>) componentsToGroupsMappingCache
                    .getOrDefault(componentTopics.getName(), new HashMap<>());
            groupDeploymentIdSet.putIfAbsent(groupConfig, groupName);
            componentsToGroupsMappingCache.put(componentTopics.getName(), groupDeploymentIdSet);
            pendingComponentsList.add(componentTopics.getName());
        });

        // Associate the groups to the dependant services based on the services it is depending on.
        while (!pendingComponentsList.isEmpty()) {
            String componentName = pendingComponentsList.get(0);
            try {
                GreengrassService greengrassService = kernel.locate(componentName);
                Map<String, Object> groupNamesForComponent = (Map<String, Object>) componentsToGroupsMappingCache
                        .getOrDefault(greengrassService.getName(), new HashMap<>());

                greengrassService.getDependencies().forEach((greengrassService1, dependencyType) -> {
                    pendingComponentsList.add(greengrassService1.getName());
                    Map<String, Object> groupNamesForDependentComponent =
                            (Map<String, Object>) componentsToGroupsMappingCache
                                    .getOrDefault(greengrassService1.getName(), new HashMap<>());
                    groupNamesForDependentComponent.putAll(groupNamesForComponent);
                    componentsToGroupsMappingCache.put(greengrassService1.getName(),
                            groupNamesForDependentComponent);
                });
            } catch (ServiceLoadException ex) {
                logger.atError().cause(ex).log("Unable to get status for {}.", componentName);
            }
            pendingComponentsList.remove(0);
        }

        if (componentsToGroupsTopics != null) {
            componentsToGroupsTopics.replaceAndWait(componentsToGroupsMappingCache);
        }

    }

    /**
     * Gets the list of all the groups that the component is a part of.
     *
     * @param componentName The name of the component.
     * @return The list of groups the component is a part of.
     */
    public Set<String> getGroupNamesForUserComponent(String componentName) {
        Topics componentsToGroupsTopics = config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS);

        Set<String> componentGroups = new HashSet<>();
        if (componentsToGroupsTopics != null) {
            Topics groupsTopics = componentsToGroupsTopics.lookupTopics(componentName);
            groupsTopics.children.values().stream().map(n -> (Topic) n)
                    .forEach(topic -> {
                        String groupName = Coerce.toString(topic);
                        if (!Utils.isEmpty(groupName)) {
                            componentGroups.add(groupName);
                        }
                    });
        }
        return componentGroups;
    }

    /**
     * Gets the list of all the groups that the thing is a part of.
     * @return All the group configs.
     */
    public Set<String> getAllGroupNames() {
        Topics componentsToGroupsTopics = config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS);

        Set<String> allGroupNames = new HashSet<>();
        if (componentsToGroupsTopics != null) {
            componentsToGroupsTopics.iterator().forEachRemaining(node -> {
                Topics groupsTopics = (Topics) node;
                groupsTopics.children.values().stream().map(n -> (Topic) n)
                        .forEach(topic -> {
                            String groupName = Coerce.toString(topic);
                            if (!Utils.isEmpty(groupName)) {
                                allGroupNames.add(groupName);
                            }
                        });

            });
        }
        return allGroupNames;
    }
}
