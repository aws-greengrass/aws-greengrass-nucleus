/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;


import com.amazonaws.util.CollectionUtils;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.converter.DeploymentDocumentConverter;
import com.aws.iot.evergreen.deployment.exceptions.InvalidRequestException;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.DeploymentTask;
import com.aws.iot.evergreen.deployment.model.DeploymentTaskMetadata;
import com.aws.iot.evergreen.deployment.model.FleetConfiguration;
import com.aws.iot.evergreen.deployment.model.LocalOverrideRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.UpdateSystemSafelyService;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Named;

import static com.aws.iot.evergreen.deployment.converter.DeploymentDocumentConverter.DEFAULT_GROUP_NAME;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

@ImplementsService(name = DeploymentService.DEPLOYMENT_SERVICE_TOPICS, autostart = true)
public class DeploymentService extends EvergreenService {

    public static final String DEPLOYMENT_SERVICE_TOPICS = "DeploymentService";
    public static final String GROUP_TO_ROOT_COMPONENTS_TOPICS = "GroupToRootComponents";
    public static final String COMPONENTS_TO_GROUPS_TOPICS = "ComponentToGroups";
    public static final String GROUP_TO_ROOT_COMPONENTS_VERSION_KEY = "version";
    public static final String GROUP_TO_ROOT_COMPONENTS_GROUP_DEPLOYMENT_ID = "groupDeploymentId";

    public static final String DEPLOYMENTS_QUEUE = "deploymentsQueue";
    protected static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // TODO: These should probably become configurable parameters eventually
    private static final long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(15).toMillis();
    private static final int DEPLOYMENT_MAX_ATTEMPTS = 3;
    private static final String JOB_ID_LOG_KEY_NAME = "JobId";

    @Inject
    @Setter
    private ExecutorService executorService;
    @Inject
    private DependencyResolver dependencyResolver;
    @Inject
    private PackageManager packageManager;
    @Inject
    private KernelConfigResolver kernelConfigResolver;
    @Inject
    private DeploymentConfigMerger deploymentConfigMerger;

    @Inject
    private DeploymentStatusKeeper deploymentStatusKeeper;

    @Inject
    private Context context;

    @Inject
    private Kernel kernel;

    private DeploymentTaskMetadata currentDeploymentTaskMetadata = null;

    @Getter
    private final AtomicBoolean receivedShutdown = new AtomicBoolean(false);

    @Setter
    private long pollingFrequency = DEPLOYMENT_POLLING_FREQUENCY;

    @Inject
    @Named(DEPLOYMENTS_QUEUE)
    private LinkedBlockingQueue<Deployment> deploymentsQueue;

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
     * @param packageManager         {@link PackageManager}
     * @param kernelConfigResolver   {@link KernelConfigResolver}
     * @param deploymentConfigMerger {@link DeploymentConfigMerger}
     * @param deploymentConfigMerger {@link Kernel}
     */
    DeploymentService(Topics topics, ExecutorService executorService, DependencyResolver dependencyResolver,
                      PackageManager packageManager, KernelConfigResolver kernelConfigResolver,
                      DeploymentConfigMerger deploymentConfigMerger, DeploymentStatusKeeper deploymentStatusKeeper,
                      Context context, Kernel kernel) {
        super(topics);
        this.executorService = executorService;
        this.dependencyResolver = dependencyResolver;
        this.packageManager = packageManager;
        this.kernelConfigResolver = kernelConfigResolver;
        this.deploymentConfigMerger = deploymentConfigMerger;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.context = context;
        this.kernel = kernel;
    }

    @Override
    public void postInject() {
        super.postInject();
        // Informing kernel about IotJobsHelper and LocalDeploymentListener so kernel can instantiate,
        // inject dependencies and call post inject.
        // This is required because both the classes are independent and not evergreen services
        context.get(IotJobsHelper.class);
        context.get(LocalDeploymentListener.class);
        deploymentStatusKeeper.setDeploymentService(this);
    }

    @Override
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
            Deployment deployment = deploymentsQueue.peek();
            if (deployment != null) {
                if (currentDeploymentTaskMetadata != null && currentDeploymentTaskMetadata.getDeploymentType()
                        .equals(deployment.getDeploymentType()) && deployment.isCancelled()
                        && currentDeploymentTaskMetadata.isCancellable()) {
                    logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
                            .log("Canceling current deployment");
                    // Assuming cancel will either cancel the current deployment or wait till it finishes
                    cancelCurrentDeployment();
                }
                if (currentDeploymentTaskMetadata != null && !deployment.getDeploymentType()
                        .equals(currentDeploymentTaskMetadata.getDeploymentType())) {
                    // deployment from another source, wait till the current deployment finishes
                    continue;
                }
                if (currentDeploymentTaskMetadata != null && deployment.getId()
                        .equals(currentDeploymentTaskMetadata.getDeploymentId()) && deployment.getDeploymentType()
                        .equals(currentDeploymentTaskMetadata.getDeploymentType())) {
                    //Duplicate message and already processing this deployment so nothing is needed
                    deploymentsQueue.remove();
                    continue;
                }
                deploymentsQueue.remove();
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
        logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId())
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

                    Map<Object, Object> deploymentGroupToRootPackages = new HashMap<>();
                    // TODO: Removal of group from the mappings. Currently there is no action taken when a device is
                    //  removed from a thing group. Empty configuration is treated as a valid config for a group but
                    //  not treated as removal.
                    deploymentDocument.getDeploymentPackageConfigurationList().stream().forEach(pkgConfig -> {
                        if (pkgConfig.isRootComponent()) {
                            Map<Object, Object> pkgDetails = new HashMap<>();
                            pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, pkgConfig.getResolvedVersion());
                            pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_DEPLOYMENT_ID,
                                    deploymentDocument.getDeploymentId());
                            deploymentGroupToRootPackages.put(pkgConfig.getPackageName(), pkgDetails);
                        }
                    });
                    deploymentGroupTopics.replaceAndWait(deploymentGroupToRootPackages);
                    setComponentsToGroupsMapping(deploymentGroupTopics);
                    deploymentStatusKeeper
                            .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                    currentDeploymentTaskMetadata.getDeploymentType(), JobStatus.SUCCEEDED,
                                    statusDetails);
                } else {
                    if (result.getFailureCause() != null) {
                        statusDetails.put("deployment-failure-cause", result.getFailureCause().toString());
                    }
                    //TODO: Update the groupToRootPackages mapping in config for the case where there is no rollback
                    // and now the packages deployed for the current group are not the same as before starting
                    // deployment
                    deploymentStatusKeeper
                            .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                    currentDeploymentTaskMetadata.getDeploymentType(), JobStatus.FAILED, statusDetails);
                }
            }
        } catch (ExecutionException e) {
            logger.atError().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentTaskMetadata.getDeploymentId()).setCause(e)
                    .log("Caught exception while getting the status of the Job");
            Throwable t = e.getCause();
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", t.getMessage());
            if (t instanceof NonRetryableDeploymentTaskFailureException
                    || currentDeploymentTaskMetadata.getDeploymentAttemptCount().get() >= DEPLOYMENT_MAX_ATTEMPTS) {
                deploymentStatusKeeper
                        .persistAndPublishDeploymentStatus(currentDeploymentTaskMetadata.getDeploymentId(),
                                currentDeploymentTaskMetadata.getDeploymentType(), JobStatus.FAILED, statusDetails);
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
        if (currentDeploymentTaskMetadata.getDeploymentResultFuture() != null) {
            if (currentDeploymentTaskMetadata.getDeploymentResultFuture().isDone()
                    || !currentDeploymentTaskMetadata.isCancellable()) {
                logger.atInfo().log("Deployment already finished processing or cannot be cancelled");
            } else {
                boolean canCancelDeployment = context.get(UpdateSystemSafelyService.class).discardPendingUpdateAction(
                        ((DefaultDeploymentTask) currentDeploymentTaskMetadata.getDeploymentTask())
                                .getDeploymentDocument().getDeploymentId());
                if (canCancelDeployment) {
                    currentDeploymentTaskMetadata.getDeploymentResultFuture().cancel(true);
                    logger.atInfo().kv("deploymentId", currentDeploymentTaskMetadata.getDeploymentId())
                            .log("Deployment was cancelled");
                } else {
                    logger.atInfo().kv("deploymentId", currentDeploymentTaskMetadata.getDeploymentId())
                            .log("Deployment is in a stage where it cannot be cancelled,"
                                    + "need to wait for it to finish");
                    try {
                        currentDeploymentTaskMetadata.getDeploymentResultFuture().get();
                    } catch (ExecutionException | InterruptedException e) {
                        logger.atError().kv("deploymentId", currentDeploymentTaskMetadata.getDeploymentId())
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
        logger.atInfo().kv("DeploymentId", deployment.getId())
                .kv("DeploymentType", deployment.getDeploymentType().toString())
                .log("Received deployment in the queue");

        DeploymentTask deploymentTask;
        boolean cancellable = true;
        if (Deployment.DeploymentStage.DEFAULT.equals(deployment.getDeploymentStage())) {
            deploymentTask = createDefaultNewDeployment(deployment);
        } else {
            deploymentTask = createKernelUpdateDeployment(deployment);
            cancellable = false;
        }
        if (deploymentTask == null) {
            return;
        }
        deploymentStatusKeeper.persistAndPublishDeploymentStatus(deployment.getId(), deployment.getDeploymentType(),
                JobStatus.IN_PROGRESS, new HashMap<>());
        Future<DeploymentResult> process = executorService.submit(deploymentTask);
        logger.atInfo().kv("deployment", deployment.getId()).log("Started deployment execution");

        currentDeploymentTaskMetadata =
                new DeploymentTaskMetadata(deploymentTask, process, deployment.getId(), deployment.getDeploymentType(),
                        new AtomicInteger(1), deployment.getDeploymentDocumentObj(), cancellable);
    }

    private KernelUpdateDeploymentTask createKernelUpdateDeployment(Deployment deployment) {
        return new KernelUpdateDeploymentTask(kernel, logger, deployment);
    }

    private DefaultDeploymentTask createDefaultNewDeployment(Deployment deployment) {
        try {
            logger.atInfo().kv("document", deployment.getDeploymentDocument())
                    .log("Received deployment document in queue");
            parseAndValidateJobDocument(deployment);
        } catch (InvalidRequestException e) {
            logger.atError().kv(JOB_ID_LOG_KEY_NAME, deployment.getId())
                    .kv("DeploymentType", deployment.getDeploymentType().toString())
                    .log("Invalid document for deployment");
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", e.getMessage());
            deploymentStatusKeeper.persistAndPublishDeploymentStatus(deployment.getId(), deployment.getDeploymentType(),
                    JobStatus.FAILED, statusDetails);
            return null;
        }
        return new DefaultDeploymentTask(dependencyResolver, packageManager, kernelConfigResolver,
                deploymentConfigMerger, logger, deployment.getDeploymentDocumentObj(), config);
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
                    LocalOverrideRequest localOverrideRequest =
                            OBJECT_MAPPER.readValue(jobDocumentString, LocalOverrideRequest.class);
                    Map<String, String> rootComponents = new HashMap<>();
                    Set<String> rootComponentsInRequestedGroup = new HashSet<>();
                    config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS).lookupTopics(
                            localOverrideRequest.getGroupName() == null ? DEFAULT_GROUP_NAME
                                    : localOverrideRequest.getGroupName())
                            .deepForEachTopic(t -> rootComponentsInRequestedGroup.add(t.getName()));

                    //TODO: pulling the versions from kernel. Can pull it form the config itself.
                    // Confirm if pulling from config should not break any use case for local
                    if (!CollectionUtils.isNullOrEmpty(rootComponentsInRequestedGroup)) {
                        rootComponentsInRequestedGroup.stream().forEach(c -> {
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
                    FleetConfiguration config = OBJECT_MAPPER.readValue(jobDocumentString, FleetConfiguration.class);
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

    void setDeploymentsQueue(LinkedBlockingQueue<Deployment> deploymentsQueue) {
        this.deploymentsQueue = deploymentsQueue;
    }

    public DeploymentTaskMetadata getCurrentDeploymentTaskMetadata() {
        return currentDeploymentTaskMetadata;
    }

    private void setComponentsToGroupsMapping(Topics groupsToRootComponents) {
         if (groupsToRootComponents.children == null || groupsToRootComponents.children.size() == 0) {
            return;
        }
        List<String> pendingComponentsList = new LinkedList<>();
        Map<Object, Object> componentsToGroupsMappingCache = new ConcurrentHashMap<>();
        Topics componentsToGroupsTopics;

        componentsToGroupsTopics = getConfig().lookupTopics(COMPONENTS_TO_GROUPS_TOPICS);
        // Get all the groups associated to the root components.
        groupsToRootComponents.iterator().forEachRemaining(groupNode -> {
            Topics componentTopics = (Topics) groupNode;

            Topic lookup = componentTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_DEPLOYMENT_ID);
            String groupDeploymentId = (String) lookup.getOnce();

            Map<Object, Object> groupDeploymentIdSet = (Map<Object, Object>) componentsToGroupsMappingCache
                    .getOrDefault(componentTopics.getName(), new HashMap<>());
            groupDeploymentIdSet.putIfAbsent(groupDeploymentId, true);
            componentsToGroupsMappingCache.put(componentTopics.getName(), groupDeploymentIdSet);
            pendingComponentsList.add(componentTopics.getName());
        });

        // Associate the groups to the dependant services based on the services it is depending on.
        while (!pendingComponentsList.isEmpty()) {
            String componentName = pendingComponentsList.get(0);
            try {
                EvergreenService evergreenService = kernel.locate(componentName);
                Map<Object, Object> groupNamesForComponent = (Map<Object, Object>) componentsToGroupsMappingCache
                        .getOrDefault(evergreenService.getName(), new HashMap<>());

                evergreenService.getDependencies().forEach((evergreenService1, dependencyType) -> {
                    pendingComponentsList.add(evergreenService1.getName());
                    Map<Object, Object> groupNamesForDependentComponent =
                            (Map<Object, Object>) componentsToGroupsMappingCache
                                    .getOrDefault(evergreenService1.getName(), new HashMap());
                    groupNamesForDependentComponent.putAll(groupNamesForComponent);
                    componentsToGroupsMappingCache.put(evergreenService1.getName(),
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
}
