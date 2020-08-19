package com.aws.iot.evergreen.integrationtests.kernel.resource;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.kernel.EvergreenService;

@ImplementsService(name = "plugin-dependency")
public class PluginDependencyService extends EvergreenService {
    public PluginDependencyService(Topics topics) {
        super(topics);
    }
}
