/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import android.content.pm.PackageInfo;
import android.os.Build;

import com.vdurmont.semver4j.Semver;
//import lombok.AllArgsConstructor;
import lombok.Value;

@Value
//@AllArgsConstructor
public class AndroidPackageIdentifier {
    String name;
    Semver version;
    long versionCode;

    public AndroidPackageIdentifier(PackageInfo packageInfo) {
        name = packageInfo.packageName;
        version = new Semver(packageInfo.versionName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            versionCode = packageInfo.getLongVersionCode();
        } else {
            versionCode = packageInfo.versionCode;
        }
    }

    @Override
    public String toString() {
        return String.format("%s-v%s(%d)", name, version, versionCode);
    }
}
