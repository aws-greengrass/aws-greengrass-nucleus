package com.aws.greengrass.ipc.common;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class GGEventStreamConnectMessage {
    String authToken;
}
