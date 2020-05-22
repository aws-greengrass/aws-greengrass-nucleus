package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.LocalOverrideRequest;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NoArgsConstructor;

import java.util.concurrent.LinkedBlockingQueue;
import javax.inject.Inject;
import javax.inject.Named;

import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentType;

@NoArgsConstructor
public class LocalDeploymentListener {

    public static final String LOCAL_DEPLOYMENT_ID_PREFIX = "Local-";
    private static final String DEPLOYMENT_ID_LOG_KEY_NAME = "DeploymentId";
    private static Logger logger = LogManager.getLogger(LocalDeploymentListener.class);

    @Inject
    @Named("deploymentsQueue")
    private LinkedBlockingQueue<Deployment> deploymentsQueue;

    //TODO: LocalDeploymentListener should register with IPC to expose submitLocalDeployment
    /**
     * schedules a deployment with deployment service.
     * @param localOverrideRequestStr serialized localOverrideRequestStr
     * @return true if deployment was scheduled
     */
    public boolean submitLocalDeployment(String localOverrideRequestStr) {

        LocalOverrideRequest request = null;

        // TODO request validation and reject handling
        try {
            request = new ObjectMapper().readValue(localOverrideRequestStr, LocalOverrideRequest.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        Deployment deployment = new Deployment(localOverrideRequestStr, DeploymentType.LOCAL, request.getRequestId());
        if (deploymentsQueue != null && deploymentsQueue.offer(deployment)) {
            logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, request.getRequestId())
                    .log("Added local deployment to the queue");
            return true;
        }
        return false;
    }

}
