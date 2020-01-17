package com.aws.iot.evergreen.builtin.services.servicediscovery;

import com.aws.iot.evergreen.ipc.services.servicediscovery.Resource;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SDAResource {
    private Resource resource;
    private boolean publishedToDNSSD;
    private String owningService;
}
