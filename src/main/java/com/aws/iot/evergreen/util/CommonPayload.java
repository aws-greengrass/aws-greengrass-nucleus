package com.aws.iot.evergreen.util;

import java.util.List;

public interface CommonPayload<T> {
    void setVariablePayload(List<T> variablePayload);
}
