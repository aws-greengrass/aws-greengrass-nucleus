package com.aws.iot.evergreen.builtin.services.servicediscovery;

import com.aws.iot.evergreen.ipc.Ipc;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SDAResource {
    private Ipc.Resource resource;
    private boolean publishedToDNSSD;
    private String owningService;
}
