/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.InvalidRequestException;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionSummary;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(30).toMillis();
    //TODO: Change this to be taken from config or user input. Maybe as part of deployment document
    private static final Path LOCAL_ARTIFACT_SOURCE =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    public static final String DEVICE_PARAM_THING_NAME = "thingName";
    public static final String DEVICE_PARAM_MQTT_CLIENT_ENDPOINT = "mqttClientEndpoint";
    public static final String DEVICE_PARAM_PRIVATE_KEY_PATH = "privateKeyPath";
    public static final String DEVICE_PARAM_CERTIFICATE_FILE_PATH = "certificateFilePath";
    public static final String DEVICE_PARAM_ROOT_CA_PATH = "rootCaPath";

    @Inject
    private ExecutorService executorService;
    @Inject
    private Kernel kernel;
    @Inject
    private IotJobsHelperFactory iotJobsHelperFactory;
    @Inject
    private DependencyResolver dependencyResolver;
    @Inject
    private PackageCache packageCache;
    @Inject
    private KernelConfigResolver kernelConfigResolver;

    private IotJobsHelper iotJobsHelper;
    private final AtomicBoolean receivedShutdown = new AtomicBoolean(false);
    private boolean errored;
    @Getter
    private Future<Void> currentProcessStatus = null;
    private String currentJobId;

    @Setter
    private long pollingFrequency = DEPLOYMENT_POLLING_FREQUENCY;

    private MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            if (errorCode != 0) {
                logger.error("Connection interrupted: " + errorCode + ": " + CRT.awsErrorString(errorCode));
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            logger.info("Connection resumed: " + (sessionPresent ? "existing session" : "clean session"));
        }
    };

    private final Consumer<JobExecutionsChangedEvent> eventHandler = event -> {
        /*
         * This message is received when either of these things happen
         * 1. Last job completed (successful/failed)
         * 2. A new job was queued
         * 3. A job was cancelled
         * This message receives the list of Queued and InProgress jobs at the time of this message
         */
        Map<JobStatus, List<JobExecutionSummary>> jobs = event.jobs;
        if (jobs.containsKey(JobStatus.QUEUED)) {
            //Do not wait on the future in this async handler,
            //as it will block the thread which establishes
            // the MQTT connection. This will result in frozen MQTT connection
            this.iotJobsHelper.requestNextPendingJobDocument();
        }
    };

    private final Consumer<DescribeJobExecutionResponse> describeJobExecutionResponseConsumer = response -> {
        if (response.execution == null) {
            return;
        }

        JobExecutionData jobExecutionData = response.execution;
        currentJobId = jobExecutionData.jobId;
        logger.atInfo()
                .log("Received job description for job id : {} and status {}", currentJobId, jobExecutionData.status);
        logger.addDefaultKeyValue("JobId", currentJobId);
        if (jobExecutionData.status == JobStatus.IN_PROGRESS) {
            //TODO: Check the currently running process,
            // if it is same as this jobId then do nothing. If not then there is something wrong
            return;
        } else if (jobExecutionData.status == JobStatus.QUEUED) {
            //If there is a job running at this time, then it has been canceled in cloud and should be attempted to
            // be canceled here
            if (currentProcessStatus != null && !currentProcessStatus.cancel(true)) {
                //If the cancel is not successful
                return;
            }
            iotJobsHelper.updateJobStatus(currentJobId, JobStatus.IN_PROGRESS, null);

            logger.info("Updated the status of JobsId {} to {}", currentJobId, JobStatus.IN_PROGRESS);


            DeploymentTask deploymentTask;
            try {
                deploymentTask = createDeploymentTask(response.execution.jobDocument);
            } catch (InvalidRequestException e) {
                logger.atError().setCause(e).addKeyValue("message", e.getMessage())
                        .log("Caught InvalidRequestException while processing a deployment");
                HashMap<String, String> statusDetails = new HashMap<>();
                statusDetails.put("error", e.getMessage());
                updateJobAsFailed(currentJobId, statusDetails);
                return;
            }
            //Starting the job processing in another thread
            currentProcessStatus = executorService.submit(deploymentTask);
            logger.atInfo().log("Submitted the job with jobId {}", jobExecutionData.jobId);
        }

    };

    private DeploymentTask createDeploymentTask(Map<String, Object> jobDocument) throws InvalidRequestException {

        DeploymentDocument deploymentDocument = parseAndValidateJobDocument(jobDocument);
        return new DeploymentTask(dependencyResolver, packageCache, kernelConfigResolver, kernel, logger,
                deploymentDocument);
    }

    private DeploymentDocument parseAndValidateJobDocument(Map<String, Object> jobDocument)
            throws InvalidRequestException {
        if (jobDocument == null) {
            String errorMessage = "Job document cannot be empty";
            throw new InvalidRequestException(errorMessage);
        }
        DeploymentDocument deploymentDocument = null;
        try {
            String jobDocumentString = OBJECT_MAPPER.writeValueAsString(jobDocument);
            deploymentDocument = OBJECT_MAPPER.readValue(jobDocumentString, DeploymentDocument.class);
            return deploymentDocument;
        } catch (JsonProcessingException e) {
            String errorMessage = "Unable to parse the job document";
            throw new InvalidRequestException(errorMessage, e);
        }
    }

    private void updateJobAsSucceeded(String jobId, HashMap<String, String> statusDetails) {
        iotJobsHelper.updateJobStatus(jobId, JobStatus.SUCCEEDED, statusDetails);
        logger.addDefaultKeyValue("JobId", "");
    }

    private void updateJobAsFailed(String jobId, HashMap<String, String> statusDetails) {
        iotJobsHelper.updateJobStatus(jobId, JobStatus.FAILED, statusDetails);
        logger.addDefaultKeyValue("JobId", "");
    }

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
     * @param topics               The configuration coming from  kernel
     * @param iotJobsHelperFactory Factory object for creating IotJobHelper
     * @param executorService      Executor service coming from kernel
     * @param kernel               The evergreen kernel
     * @param dependencyResolver   {@link DependencyResolver}
     * @param packageCache         {@link PackageCache}
     * @param kernelConfigResolver {@link KernelConfigResolver}
     */
    public DeploymentService(Topics topics, IotJobsHelperFactory iotJobsHelperFactory, ExecutorService executorService,
                             Kernel kernel, DependencyResolver dependencyResolver, PackageCache packageCache,
                             KernelConfigResolver kernelConfigResolver) {
        super(topics);
        this.iotJobsHelperFactory = iotJobsHelperFactory;
        this.executorService = executorService;
        this.kernel = kernel;
        this.dependencyResolver = dependencyResolver;
        this.packageCache = packageCache;
        this.kernelConfigResolver = kernelConfigResolver;
    }

    @Override
    public void startup() {
        // Reset shutdown signal since we're trying to startup here
        this.receivedShutdown.set(false);
        initialize();
        logger.info("Starting up the Deployment Service");
        String thingName = getStringParameterFromConfig(DEVICE_PARAM_THING_NAME);
        //TODO: Add any other checks to verify device provisioned to communicate with Iot Cloud
        if (thingName.isEmpty()) {
            logger.info("There is no thingName assigned to this device. Cannot communicate with cloud."
                    + " Finishing deployment service");
            reportState(State.FINISHED);
            return;
        }

        try {
            initializeIotJobsHelper(thingName);
            iotJobsHelper.connectToAwsIot();
            iotJobsHelper.subscribeToEventNotifications(eventHandler);
            iotJobsHelper.subscribeToGetNextJobDecription(describeJobExecutionResponseConsumer, rejectedError -> {
                logger.error("Job subscription got rejected", rejectedError);
                //TODO: Add retry logic for subscribing
            });
            reportState(State.RUNNING);
        } catch (ExecutionException | InterruptedException ex) {
            logger.error("Caught exception in subscribing to topics", ex);
            errored = true;
            reportState(State.ERRORED);
        }
        logger.info("Running deployment service");
        while (!receivedShutdown.get() && !errored) {
            try {
                if (currentProcessStatus != null) {
                    logger.info("Getting the status of the current process");

                    currentProcessStatus.get();
                    updateJobAsSucceeded(currentJobId, null);
                    currentProcessStatus = null;
                }
                Thread.sleep(pollingFrequency);
            } catch (InterruptedException ex) {
                logger.atError().log("Interrupted while sleeping in DA");
                errored = true;
                reportState(State.ERRORED);
            } catch (ExecutionException e) {
                logger.atError().setCause(e).addKeyValue("jobId", currentJobId)
                        .log("Caught exception while getting the status of the Job");
                Throwable t = e.getCause();
                if (t instanceof NonRetryableDeploymentTaskFailureException) {
                    HashMap<String, String> statusDetails = new HashMap<>();
                    statusDetails.put("error", t.getMessage());
                    updateJobAsFailed(currentJobId, statusDetails);
                }
                currentProcessStatus = null;
                //TODO: Handle retryable error
            }
        }
    }

    @Override
    public void shutdown() {
        receivedShutdown.set(true);
        if (iotJobsHelper != null) {
            iotJobsHelper.closeConnection();
        }
    }

    private void initialize() {
        //TODO: Update then pacakge store to be used once it is designed. Probably remove this.
        this.dependencyResolver.setStore(new LocalPackageStore(LOCAL_ARTIFACT_SOURCE));
    }

    private void initializeIotJobsHelper(String thingName) {
        //TODO: Get it from bootstrap config. Path of Bootstrap config should be taken as argument to kernel?
        String privateKeyPath = kernel.deTilde(getStringParameterFromConfig(DEVICE_PARAM_PRIVATE_KEY_PATH));
        String certificateFilePath = kernel.deTilde(getStringParameterFromConfig(DEVICE_PARAM_CERTIFICATE_FILE_PATH));
        String rootCAPath = kernel.deTilde(getStringParameterFromConfig(DEVICE_PARAM_ROOT_CA_PATH));
        String clientEndpoint = getStringParameterFromConfig(DEVICE_PARAM_MQTT_CLIENT_ENDPOINT);

        logger.atInfo().kv("privateKeyPath", privateKeyPath).kv("certificatePath", certificateFilePath)
                .kv("rootCAPath", rootCAPath).kv("endpoint", clientEndpoint).kv("thingName", thingName).log();
        this.iotJobsHelper = iotJobsHelperFactory
                .getIotJobsHelper(thingName, certificateFilePath, privateKeyPath, rootCAPath, clientEndpoint,
                        callbacks);
    }

    private String getStringParameterFromConfig(String parameterName) {
        String paramValue = "";
        Topic childTopic = config.findLeafChild(parameterName);
        if (childTopic != null) {
            paramValue = childTopic.getOnce().toString();
        }
        return paramValue;
    }

    public static class IotJobsHelperFactory {

        /**
         * Returns IotJobsHelper {@link IotJobsHelper}.
         *
         * @param thingName           Iot thing name
         * @param certificateFilePath Device certificate file path
         * @param privateKeyPath      Device private key file path
         * @param rootCAPath          Root CA file path
         * @param clientEndpoint      Mqtt endpoint for the customer account
         * @param callbacks           Callback for handling Mqtt connection events
         * @return
         */
        public IotJobsHelper getIotJobsHelper(String thingName, String certificateFilePath, String privateKeyPath,
                                              String rootCAPath, String clientEndpoint,
                                              MqttClientConnectionEvents callbacks) {
            try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
                 HostResolver resolver = new HostResolver(eventLoopGroup);
                 ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
                 AwsIotMqttConnectionBuilder builder = AwsIotMqttConnectionBuilder
                         .newMtlsBuilderFromPath(certificateFilePath, privateKeyPath)) {
                builder.withCertificateAuthorityFromPath(null, rootCAPath).withEndpoint(clientEndpoint)
                        .withClientId(UUID.randomUUID().toString()).withCleanSession(true)
                        .withBootstrap(clientBootstrap).withConnectionEventCallbacks(callbacks);

                MqttClientConnection connection = builder.build();
                IotJobsClient iotJobsClient = new IotJobsClient(connection);
                return new IotJobsHelper(thingName, connection, iotJobsClient);
            }
        }
    }
}
