/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.EZPlugins;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;

public class PluginService extends GreengrassService {
    @Inject
    private EZPlugins ezPlugins;

    public PluginService(Topics topics) {
        super(topics);
    }

    public PluginService(Topics topics, Topics privateConfig) {
        super(topics, privateConfig);
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

    @Override
    public boolean isBuiltin() {
        // If this class was loaded by the original classloader, then it must be builtin
        if (getClass().getClassLoader().equals(Kernel.class.getClassLoader())) {
            return true;
        } else if (getClass().getClassLoader() instanceof URLClassLoader) {
            // If the plugin is loaded from the plugins/trusted directory, then consider it as if it were builtin
            URL[] urls = ((URLClassLoader) getClass().getClassLoader()).getURLs();
            for (URL u : urls) {
                if (new File(u.getFile()).toString().contains(ezPlugins.getTrustedCacheDirectory().toString())) {
                    return true;
                }
            }
        }

        return false;
    }
}
