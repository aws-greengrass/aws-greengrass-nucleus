/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Parameter {
    private final String name;
    private final String value;
    private final ParameterType type;

    public enum ParameterType {
        NUMBER, STRING, BOOLEAN
    }
}
