/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
#if ANDROID
package com.aws.greengrass.android.managers;

public interface AndroidComponentManager {

    boolean installPackage(String path, String packageName);

    void uninstallPackage();

    boolean startService(String packageName, String className, String action);

    boolean stopService(String packageName, String className, String action);

    boolean isPackageInstalled(String packageName, Long curLastUpdateTime);

    boolean startActivity(String packageName, String className, String action);

    boolean stopActivity(String packageName, String className, String action);

    long getPackageLastUpdateTime(String packageName);
}
#endif
