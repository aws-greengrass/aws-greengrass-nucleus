/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.util.Coerce;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class ComponentIdentifier implements Comparable<ComponentIdentifier> {
    String name;
    Semver version;

    @Override
    public String toString() {
        return String.format("%s-v%s", name, version);
    }

    public static ComponentIdentifier fromServiceTopics(Topics t) {
        return new ComponentIdentifier(t.getName(),
                new Semver(Coerce.toString(t.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY))));
    }

    /**
     * Sort in ascending order according to Semver's standard
     */
    @Override
    public int compareTo(ComponentIdentifier o) {
        return version.compareTo(o.version) * -1;   // -1 so that it is ascending
    }
}
