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

import javax.inject.Inject;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {

    private static Long DEPLOYMENT_POLLING_FREQUENCY = 30000L;//30 seconds

    @Inject
    private Log logger;

    @Inject
    private DeploymentAgent deploymentAgent;

    public DeploymentService(Topics c) {
        super(c);
    }

    @Override
    public void startup() {
        logger.log(Log.Level.Note, "Starting up the Deployment Service");

        deploymentAgent.setDeviceContext(getStringParameterFromConfig("thingName"),
                getStringParameterFromConfig("certificateFilePath"),
                getStringParameterFromConfig("privateKeyPath"),
                getStringParameterFromConfig("rootCaPath"),
                getStringParameterFromConfig("mqttClientEndpoint"));
        deploymentAgent.setupConnectionToAWSIot();

        while(true) {
            deploymentAgent.listenForDeployments();
            try {
                Thread.sleep(DEPLOYMENT_POLLING_FREQUENCY);
            } catch (InterruptedException e) {
                logger.log(Log.Level.Warn, "Deployment service interrupted");
            }
        }
    }

    @Override
    public void shutdown() {
        deploymentAgent.closeConnection();
    }

    private String getStringParameterFromConfig(String parameterName) {
        String paramValue = "";
        Node n = config.getChild(parameterName);
        if(n instanceof Topic) {
            paramValue = ((Topic) n).getOnce().toString();
        }
        return paramValue;
    }
}
