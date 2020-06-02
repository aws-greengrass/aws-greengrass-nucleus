/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;


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
import com.aws.iot.evergreen.deployment.model.FleetConfiguration;
import com.aws.iot.evergreen.deployment.model.LocalOverrideRequest;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Named;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {

    public static final String DEPLOYMENT_SERVICE_TOPICS = "DeploymentService";

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // TODO: These should probably become configurable parameters eventually
    // TODO: Deployment polling wait time can't be simply reduced now because it may result doing duplicate deployment.
    // When the wait time is reduced, the old job could already completed and removed from the queue when
    // the duplicated job comes. It can only be reduced after the IoTJobHelper::describeJobExecutionResponseConsumer
    // can dedupe properly.
    private static final long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(3).toMillis();
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

    @Getter
    private Future<DeploymentResult> currentProcessStatus = null;

    // This is very likely not thread safe. If the Deployment Service is split into multiple threads in a re-design
    // as mentioned in some other comments, this will need an update as well
    private String currentDeploymentId = null;
    private Deployment.DeploymentType currentDeploymentType = null;

    private final AtomicInteger currentJobAttemptCount = new AtomicInteger(0);
    private DeploymentTask currentDeploymentTask = null;

    @Getter
    private final AtomicBoolean receivedShutdown = new AtomicBoolean(false);

    @Setter
    private long pollingFrequency = DEPLOYMENT_POLLING_FREQUENCY;

    @Inject
    @Named("deploymentsQueue")
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
     */
    DeploymentService(Topics topics, ExecutorService executorService, DependencyResolver dependencyResolver,
                      PackageManager packageManager, KernelConfigResolver kernelConfigResolver,
                      DeploymentConfigMerger deploymentConfigMerger, DeploymentStatusKeeper deploymentStatusKeeper,
                      Context context) {
        super(topics);
        this.executorService = executorService;
        this.dependencyResolver = dependencyResolver;
        this.packageManager = packageManager;
        this.kernelConfigResolver = kernelConfigResolver;
        this.deploymentConfigMerger = deploymentConfigMerger;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.context = context;
    }

    @Override
    public void postInject() {
        super.postInject();
        // Informing kernel about IotJobsHelper and LocalDeploymentListener so kernel can instantiate,
        // inject dependencies and call post inject.
        // This is required because both the classes are independent and not evergreen services
        context.get(IotJobsHelper.class);
        context.get(LocalDeploymentListener.class);
    }

    @Override
    public void startup() throws InterruptedException {
        logger.info("Starting up the Deployment Service");
        // Reset shutdown signal since we're trying to startup here
        this.receivedShutdown.set(false);

        reportState(State.RUNNING);
        logger.info("Running deployment service");

        while (!receivedShutdown.get()) {
            if (currentProcessStatus != null && currentProcessStatus.isDone()) {
                finishCurrentDeployment();
            }
            //Cannot wait on queue because need to listen to queue as well as the currentProcessStatus future.
            //One thread cannot wait on both. If we want to make this completely event driven then we need to put
            // the waiting on currentProcessStatus in its own thread. I currently choose to not do this.
            Deployment deployment = deploymentsQueue.peek();
            if (deployment != null) {
                if (currentDeploymentType != null && !deployment.getDeploymentType().equals(currentDeploymentType)) {
                    // deployment from another source, wait till the current deployment finish
                    continue;
                }
                if (currentDeploymentId != null && currentDeploymentType != null) {
                    if (deployment.getId().equals(currentDeploymentId) && deployment.getDeploymentType()
                            .equals(currentDeploymentType)) {
                        //Duplicate message and already processing this deployment so nothing is needed
                        deploymentsQueue.remove();
                        continue;
                    } else {
                        logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentId).log("Canceling the job");
                        //Assuming cancel will either cancel the current job or wait till it finishes
                        cancelCurrentDeployment();
                    }
                }
                deploymentsQueue.remove();
                createNewDeployment(deployment);
            }
            Thread.sleep(pollingFrequency);
        }
    }

    @Override
    public void shutdown() {
        receivedShutdown.set(true);
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void finishCurrentDeployment() throws InterruptedException {
        logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentId).log("Current deployment finished");
        try {
            // No timeout is set here. Detection of error is delegated to downstream components like
            // dependency resolver, package downloader, kernel which will have more visibility
            // if something is going wrong
            DeploymentResult result = currentProcessStatus.get();
            if (result != null) {
                DeploymentResult.DeploymentStatus deploymentStatus = result.getDeploymentStatus();
                Map<String, String> statusDetails = new HashMap<>();
                statusDetails.put("detailed-deployment-status", deploymentStatus.name());
                if (deploymentStatus.equals(DeploymentResult.DeploymentStatus.SUCCESSFUL)) {
                    deploymentStatusKeeper.persistAndPublishDeploymentStatus(currentDeploymentId, currentDeploymentType,
                            JobStatus.SUCCEEDED, statusDetails);
                } else {
                    if (result.getFailureCause() != null) {
                        statusDetails.put("deployment-failure-cause", result.getFailureCause().toString());
                    }
                    deploymentStatusKeeper.persistAndPublishDeploymentStatus(currentDeploymentId, currentDeploymentType,
                            JobStatus.FAILED, statusDetails);
                }
            }
            currentJobAttemptCount.set(0);
        } catch (ExecutionException e) {
            logger.atError().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentId).setCause(e)
                    .log("Caught exception while getting the status of the Job");
            Throwable t = e.getCause();
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", t.getMessage());
            if (t instanceof NonRetryableDeploymentTaskFailureException
                    || currentJobAttemptCount.get() >= DEPLOYMENT_MAX_ATTEMPTS) {
                deploymentStatusKeeper
                        .persistAndPublishDeploymentStatus(currentDeploymentId, currentDeploymentType, JobStatus.FAILED,
                                statusDetails);
                currentJobAttemptCount.set(0);
            } else if (t instanceof RetryableDeploymentTaskFailureException) {
                // Resubmit task, increment attempt count and return
                currentProcessStatus = executorService.submit(currentDeploymentTask);
                currentJobAttemptCount.incrementAndGet();
                return;
            }
        }
        // Setting this to null to indicate there is not current deployment being processed
        // Did not use optionals over null due to performance
        //TODO: find a better way to track the current deployment details.
        currentProcessStatus = null;
        currentDeploymentId = null;
        currentDeploymentType = null;
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void cancelCurrentDeployment() {
        //TODO: Make the deployment task be able to handle the interrupt
        // and wait till the job gets cancelled or is finished
        if (currentProcessStatus != null) {
            currentProcessStatus.cancel(true);
            currentProcessStatus = null;
            currentDeploymentId = null;
            currentDeploymentType = null;
        }
    }


    private void createNewDeployment(Deployment deployment) {
        logger.atInfo().kv("DeploymentId", deployment.getId())
                .kv("DeploymentType", deployment.getDeploymentType().toString())
                .log("Received deployment in the queue");

        DeploymentDocument deploymentDocument;
        try {
            logger.atInfo().kv("document", deployment.getDeploymentDocument())
                    .log("Recevied deployment document in queue");
            deploymentDocument = parseAndValidateJobDocument(deployment);
        } catch (InvalidRequestException e) {
            logger.atError().kv(JOB_ID_LOG_KEY_NAME, deployment.getId())
                    .kv("DeploymentType", deployment.getDeploymentType().toString())
                    .log("Invalid document for deployment");
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", e.getMessage());
            deploymentStatusKeeper.persistAndPublishDeploymentStatus(deployment.getId(), deployment.getDeploymentType(),
                    JobStatus.FAILED, statusDetails);
            return;
        }
        currentDeploymentTask =
                new DeploymentTask(dependencyResolver, packageManager, kernelConfigResolver, deploymentConfigMerger,
                        logger, deploymentDocument);
        deploymentStatusKeeper.persistAndPublishDeploymentStatus(deployment.getId(), deployment.getDeploymentType(),
                JobStatus.IN_PROGRESS, new HashMap<>());
        currentProcessStatus = executorService.submit(currentDeploymentTask);

        // newIotJobsDeployment will only be called at first attempt
        currentJobAttemptCount.set(1);
        currentDeploymentId = deployment.getId();
        currentDeploymentType = deployment.getDeploymentType();
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

                    Map<String, String> rootComponents = kernel.getRunningCustomRootComponents();

                    document = DeploymentDocumentConverter
                            .convertFromLocalOverrideRequestAndRoot(localOverrideRequest, rootComponents);
                    break;
                case IOT_JOBS:
                    FleetConfiguration config = OBJECT_MAPPER.readValue(jobDocumentString, FleetConfiguration.class);
                    document = new DeploymentDocument(config);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid deployment type: " + deployment.getDeploymentType());
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidRequestException("Unable to parse the job document", e);
        }
        return document;
    }


    void setDeploymentsQueue(LinkedBlockingQueue<Deployment> deploymentsQueue) {
        this.deploymentsQueue = deploymentsQueue;
    }
}
