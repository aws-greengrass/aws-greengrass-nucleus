/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class AndroidPackageIdentifier {
    String name;
    Semver version;
    long versionCode;

    @Override
    public String toString() {
        return String.format("%s-v%s(%d)", name, version, versionCode);
    }
}
