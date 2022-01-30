/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

public interface AndroidComponentManager {

    boolean installPackage(String path, String packageName);

    void uninstallPackage();

    boolean isPackageInstalled(String packageName, Long curLastUpdateTime);

    boolean sendActivityAction(String packageName, String className, String action);

    boolean sendServiceAction(String packageName, String className, String action);

    long getPackageLastUpdateTime(String packageName);
}
