/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.util.Coerce;
import com.vdurmont.semver4j.Semver;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;


@Data
@Builder
@RequiredArgsConstructor
public class ComponentIdentifier implements Comparable<ComponentIdentifier> {
    @NonNull String name;
    @NonNull Semver version;
    String arn; // AWS Resource Name for the component and version

    @Override
    public String toString() {
        return String.format("%s-v%s", name, version);
    }

    public static ComponentIdentifier fromServiceTopics(Topics t) {
        return new ComponentIdentifier(t.getName(),
                new Semver(Coerce.toString(t.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY))));
    }

    @Override
    public int compareTo(ComponentIdentifier o) {
        return version.compareTo(o.version) * -1;
    }
}
