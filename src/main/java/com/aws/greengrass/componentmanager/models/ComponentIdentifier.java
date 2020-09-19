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
    public static final String PUBLIC_SCOPE = "PUBLIC";
    public static final String PRIVATE_SCOPE = "PRIVATE";
    String name;
    Semver version;
    //TODO considering use enum if local name occluding is necessary.
    String scope;

    /**
     * PackageIdentifier constructor.
     *
     * @param name    package name
     * @param version package version in semver
     */
    @Deprecated  //scope needs to be recorded locally, switch to use all args constructor
    public ComponentIdentifier(String name, Semver version) {
        this.name = name;
        this.version = version;
        //hardcode to 'private' before refactoring caller of this constructor
        this.scope = PRIVATE_SCOPE;
    }

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
