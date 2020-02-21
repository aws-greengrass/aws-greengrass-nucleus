/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {

    private static Long DEPLOYMENT_POLLING_FREQUENCY = 30000L;//30 seconds

    @Inject
    private Log logger;

    @Inject
    private IotJobsHelper iotJobsHelper;

    public DeploymentService(Topics c) {
        super(c);
    }

    @Override
    public void startup() {
        logger.log(Log.Level.Note, "Starting up the Deployment Service");

        iotJobsHelper.setDeviceContext(getStringParameterFromConfig("thingName"),
                getStringParameterFromConfig("certificateFilePath"),
                getStringParameterFromConfig("privateKeyPath"),
                getStringParameterFromConfig("rootCaPath"),
                getStringParameterFromConfig("mqttClientEndpoint"));
        iotJobsHelper.setupConnectionToAWSIot();

        while (true) {
            try {
                List<String> queuedJobs = iotJobsHelper.getAllQueuedJobs().get();
                for(String jobId : queuedJobs) {
                    Map<String, Object> jobDocument = iotJobsHelper.getJobDetails(jobId).get();
                    iotJobsHelper.updateJobStatus(jobId, JobStatus.IN_PROGRESS);
                }
                Thread.sleep(DEPLOYMENT_POLLING_FREQUENCY);
            }catch (CrtRuntimeException | InterruptedException | ExecutionException ex) {
                logger.log(Log.Level.Error,"Exception encountered: " + ex.toString());
                shutdown();
                return;
            }
        }
    }

    @Override
    public void shutdown() {
        iotJobsHelper.closeConnection();
    }

    private String getStringParameterFromConfig(String parameterName) {
        String paramValue = "";
        Node n = config.getChild(parameterName);
        if (n instanceof Topic) {
            paramValue = ((Topic) n).getOnce().toString();
        }
        return paramValue;
    }
}
