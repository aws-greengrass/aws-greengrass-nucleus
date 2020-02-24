/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {

    private static Long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(30).toMillis();

    @Inject
    private Log logger;
    @Inject
    private IotJobsHelper iotJobsHelper;
    private boolean receivedShutdown;

    public DeploymentService(Topics c) {
        super(c);
    }

    @Override
    public void startup() {
        logger.log(Log.Level.Note, "Starting up the Deployment Service");
        String thingName = getStringParameterFromConfig("thingName");
        if (thingName.isEmpty()) {
            logger.log(Log.Level.Note, "There is no thingName assigned to this device. Cannot communicate with cloud."
                    + " Finishing deployment service");
            reportState(State.Finished);
            return;
        }
        String env_home = System.getenv("HOME");
        String privateKeyPath = env_home + getStringParameterFromConfig("privateKeyPath");
        String certificateFilePath = env_home + getStringParameterFromConfig("certificateFilePath");
        String rootCAPath = env_home + getStringParameterFromConfig("rootCaPath");

        iotJobsHelper.setDeviceContext(thingName, certificateFilePath, privateKeyPath, rootCAPath,
                getStringParameterFromConfig("mqttClientEndpoint"));
        iotJobsHelper.setupConnectionToAWSIot();
        reportState(State.Running);

        while (true) {
            try {
                if (receivedShutdown) {
                    break;
                }
                List<String> queuedJobs = iotJobsHelper.getAllQueuedJobs().get();
                for (String jobId : queuedJobs) {
                    Map<String, Object> jobDocument = iotJobsHelper.getJobDetails(jobId).get();
                    if (jobDocument == null) {
                        iotJobsHelper.updateJobStatus(jobId, JobStatus.FAILED,
                                (HashMap) Collections.singletonMap("JobDocument", "Empty")).get();
                        continue;
                    }
                    //TODO: Add validation for Job Document
                    iotJobsHelper.updateJobStatus(jobId, JobStatus.IN_PROGRESS, null).get();
                }
                Thread.sleep(DEPLOYMENT_POLLING_FREQUENCY);
            } catch (CrtRuntimeException | InterruptedException | ExecutionException ex) {
                logger.log(Log.Level.Error, "Exception encountered: " + ex.toString());
                errored("Caught exception in deployment service", ex);
                return;
            }
        }
    }

    @Override
    public void shutdown() {
        receivedShutdown = true;
        iotJobsHelper.closeConnection();
    }

    private String getStringParameterFromConfig(String parameterName) {
        String paramValue = "";
        Topic childTopic = config.findLeafChild(parameterName);
        if (childTopic != null) {
            paramValue = childTopic.getOnce().toString();
        }
        return paramValue;
    }

}
