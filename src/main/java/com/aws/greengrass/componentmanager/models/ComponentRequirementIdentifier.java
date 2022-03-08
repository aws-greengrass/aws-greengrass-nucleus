/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

import com.vdurmont.semver4j.Requirement;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class ComponentRequirementIdentifier {
    String name;
    Requirement versionRequirement;

    @Override
    public String toString() {
        return String.format("%s-v%s", name, versionRequirement);
    }
}
