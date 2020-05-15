package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.concurrent.LinkedBlockingQueue;

import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentType;

@NoArgsConstructor
public class LocalDeploymentListener {

    public static final String LOCAL_DEPLOYMENT_ID_PREFIX = "Local-";
    private static final String DEPLOYMENT_ID_LOG_KEY_NAME = "DeploymentId";
    private static Logger logger = LogManager.getLogger(LocalDeploymentListener.class);
    @Setter
    private LinkedBlockingQueue<Deployment> deploymentsQueue;

    //TODO: LocalDeploymentListener should register with IPC to submitLocalDeployment
    /** schedules a deployment with deployment service.
     * @param deploymentDocument document configuration
     * @return true if deployment was scheduled
     */
    public boolean submitLocalDeployment(String deploymentDocument) {
        String localDeploymentIdentifier = LOCAL_DEPLOYMENT_ID_PREFIX + System.currentTimeMillis();
        Deployment deployment = new Deployment(deploymentDocument, DeploymentType.LOCAL, localDeploymentIdentifier);
        if (deploymentsQueue != null && deploymentsQueue.offer(deployment)) {
            logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, localDeploymentIdentifier)
                    .log("Added local deployment to the queue");
            return true;
        }
        return false;
    }

}
