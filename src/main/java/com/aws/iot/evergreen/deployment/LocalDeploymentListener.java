package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.vdurmont.semver4j.Semver;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import javax.inject.Inject;
import javax.inject.Named;

import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentType;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

@NoArgsConstructor
public class LocalDeploymentListener {

    public static final String LOCAL_DEPLOYMENT_ID_PREFIX = "Local-";
    private static final String DEPLOYMENT_ID_LOG_KEY_NAME = "DeploymentId";
    private static Logger logger = LogManager.getLogger(LocalDeploymentListener.class);

    @Inject
    @Named("deploymentsQueue")
    private LinkedBlockingQueue<Deployment> deploymentsQueue;

    @Inject
    private Kernel kernel;

    //TODO: LocalDeploymentListener should register with IPC to expose submitLocalDeployment
    //TODO: the interface is not finalized yet, this is more an example.
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

    /**
     * Fetch root packages and thier version from the kernel.
     * @return returns packageName, version as a map
     */
    public Map<String,String> getRootPackageNameAndVersion() {

        Map<String, String> rootPackageNameAndVersionMap = new HashMap<>();
        for (EvergreenService service : kernel.getMain().getDependencies().keySet()) {
            Topic version = service.getConfig().find(VERSION_CONFIG_KEY);
            if (version == null) {
                // version is not currently available for services that ship with the kernel
                continue;
            }
            rootPackageNameAndVersionMap.put(service.getName(), ((Semver) version.getOnce()).getValue());
        }
        return rootPackageNameAndVersionMap;
    }
}
