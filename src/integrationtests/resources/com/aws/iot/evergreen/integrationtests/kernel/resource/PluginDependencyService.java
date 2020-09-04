package com.aws.iot.evergreen.integrationtests.kernel.resource;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.kernel.PluginService;

@ImplementsService(name = "plugin-dependency")
public class PluginDependencyService extends PluginService {
    public PluginDependencyService(Topics topics) {
        super(topics);
    }
}
