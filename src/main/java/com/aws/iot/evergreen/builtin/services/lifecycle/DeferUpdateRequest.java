package com.aws.iot.evergreen.builtin.services.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeferUpdateRequest {

    String componentName;
    String message;
    /**
     Estimated time in milliseconds after which component will be willing to be disrupted.
     If the returned value is zero the handler is granting permission to be disrupted.
     Otherwise, it will be asked again later
     */
    long recheckTimeInMs;
}
