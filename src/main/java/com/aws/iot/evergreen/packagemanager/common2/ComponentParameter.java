/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.iot.evergreen.packagemanager.common2;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Builder;
import lombok.Value;

@JsonDeserialize(builder = ComponentParameter.ComponentParameterBuilder.class)
@Value
@Builder
public class ComponentParameter {

    String name;

    String value;

    ParameterType type;

    public enum ParameterType {
        NUMBER, STRING, BOOLEAN
    }
}
