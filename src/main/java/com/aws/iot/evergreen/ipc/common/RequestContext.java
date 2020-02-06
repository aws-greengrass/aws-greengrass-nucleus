package com.aws.iot.evergreen.ipc.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@AllArgsConstructor
@Data
@ToString
public class RequestContext {
    private String serviceName;
}
