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
import org.apache.commons.lang3.builder.CompareToBuilder;

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
     * First compare the name, then compare the version according to Semver's compareTo.
     */
    @Override
    public int compareTo(ComponentIdentifier o) {
        return new CompareToBuilder().append(name, o.name).append(version, o.version).build();
    }
}
