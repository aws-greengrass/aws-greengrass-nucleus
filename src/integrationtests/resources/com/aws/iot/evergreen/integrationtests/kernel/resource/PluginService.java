package com.aws.iot.evergreen.integrationtests.kernel.resource;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.kernel.EvergreenService;

@ImplementsService(name = "plugin")
public class PluginService extends EvergreenService {
    public PluginService(Topics topics) {
        super(topics);
    }
}
