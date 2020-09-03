package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;

import java.util.Map;

import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

public class PluginService extends EvergreenService {
    public PluginService(Topics topics) {
        super(topics);
    }

    /**
     * Check if bootstrap step needs to run during service update. Called during deployments to determine deployment
     * workflow.
     *
     * @param newServiceConfig new service config for the update
     * @return
     */
    @Override
    public boolean isBootstrapRequired(Map<String, Object> newServiceConfig) {
        Topic versionTopic = getConfig().find(VERSION_CONFIG_KEY);
        if (versionTopic == null) {
            logger.atTrace().log("Bootstrap is required: current service version unknown");
            return true;
        }
        if (!versionTopic.getOnce().equals(newServiceConfig.get(VERSION_CONFIG_KEY))) {
            logger.atTrace().log("Bootstrap is required: service version changed");
            return true;
        }
        logger.atTrace().log("Bootstrap is not required: service version unchanged");
        return false;
    }

    @Override
    public int bootstrap() {
        return REQUEST_RESTART;
    }
}
