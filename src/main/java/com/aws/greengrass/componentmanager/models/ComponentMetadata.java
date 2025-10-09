/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.Map;

@Value
@AllArgsConstructor
public class ComponentMetadata implements Comparable<ComponentMetadata> {
    ComponentIdentifier componentIdentifier;

    Map<String, String> dependencies; // from dependency package name to version requirement

    @Override
    public int compareTo(ComponentMetadata o) {
        return componentIdentifier.compareTo(o.componentIdentifier);
    }
}
