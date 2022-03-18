/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import androidx.test.core.app.ApplicationProvider;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static android.content.Intent.ACTION_VIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class})
public class AndroidBasePackageManagerTest {
    private static Logger logger = LogManager.getLogger(AndroidBasePackageManagerTest.class);

    Context context;
    File tempFileDir;

    @Mock
    AndroidContextProvider contextProvider;

    @Mock(lenient = true)
    PackageManager packageManager;

    @Mock
    PackageInstaller packageInstaller;

    PackageInfo createPackageInfo(String name, long version, String versionName, long lastUpdateTime) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = name;
        packageInfo.setLongVersionCode(version);
        packageInfo.versionName = versionName;
        packageInfo.lastUpdateTime = lastUpdateTime;
        return packageInfo;
    }

    @BeforeEach
    public void setup() throws NoSuchMethodException {
        context = spy(ApplicationProvider.getApplicationContext());
        tempFileDir = Paths.get(context.getFilesDir().toString(),
                "greengrass/v2/packages/artifacts-unarchived").toFile();
        tempFileDir.mkdirs();
        when(contextProvider.getContext()).thenReturn(context);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(packageManager.getPackageInstaller()).thenReturn(packageInstaller);
    }

    @Test
    void GIVEN_package_not_installed_WHEN_get_package_info_THEN_package_info_is_null() throws Exception {
        AndroidBasePackageManager androidBasePackageManager = spy(new AndroidBasePackageManager(contextProvider));
        when(packageManager.getPackageInfo("samplePackage", 0)).thenThrow(new PackageManager.NameNotFoundException());
        assertNull(androidBasePackageManager.getPackageInfo("samplePackage"));
    }

    @Test
    void GIVEN_package_not_installed_WHEN_install_THEN_install_intent_activity_started() throws Exception {
        AndroidBasePackageManager androidBasePackageManager = spy(new AndroidBasePackageManager(contextProvider));

        // Verify package is not installed
        when(packageManager.getPackageInfo("samplePackage", 0)).
                thenThrow(new PackageManager.NameNotFoundException());
        assertNull(androidBasePackageManager.getPackageInfo("samplePackage"));

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        PackageInfo packageInfo = createPackageInfo("samplePackage", 1, "1", 0);
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0)).thenReturn(packageInfo);
        new Thread(() -> {
            try {
                androidBasePackageManager.installAPK(apkPath.toString(), "samplePackage", false, logger);
                Thread.sleep(100);
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        ArgumentCaptor<Intent> argument = ArgumentCaptor.forClass(Intent.class);
        verify(context, timeout(1000).atLeastOnce()).startActivity(argument.capture());
        assertEquals(ACTION_VIEW, argument.getValue().getAction());
    }

    @Test
    void GIVEN_package_not_installed_WHEN_install_THEN_package_installed() throws Exception {
        AndroidBasePackageManager androidBasePackageManager = spy(new AndroidBasePackageManager(contextProvider));

        PackageInfo packageInfo = createPackageInfo("samplePackage", 1, "1", 0);
        when(packageManager.getPackageInfo("samplePackage", 0)).
                thenThrow(new PackageManager.NameNotFoundException()).
                thenReturn(packageInfo);

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0)).thenReturn(packageInfo);
        androidBasePackageManager.installAPK(apkPath.toString(), "samplePackage", false, logger);
        ArgumentCaptor<Intent> argument = ArgumentCaptor.forClass(Intent.class);
        verify(context, atLeastOnce()).startActivity(argument.capture());
        assertEquals(ACTION_VIEW, argument.getValue().getAction());
    }

    @Test
    void GIVEN_package_installed_WHEN_install_same_package_THEN_installation_skipped() throws Exception {
        AndroidBasePackageManager androidBasePackageManager = spy(new AndroidBasePackageManager(contextProvider));

        PackageInfo packageInfo = createPackageInfo("samplePackage", 1, "1", 0);
        when(packageManager.getPackageInfo("samplePackage", 0)).
                thenReturn(packageInfo);

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0)).thenReturn(packageInfo);
        androidBasePackageManager.installAPK(apkPath.toString(), "samplePackage", false, logger);
        verify(context, never()).startActivity(any());
    }

    @Test
    void GIVEN_package_installed_with_higher_version_WHEN_force_reinstall_THEN_uninstall_called()
            throws Exception {
        AndroidBasePackageManager androidBasePackageManager = spy(new AndroidBasePackageManager(contextProvider));

        PackageInfo packageInfoBeforeInstall = createPackageInfo(
                "samplePackage", 2, "2", 0);
        PackageInfo packageInfoAfterInstall = createPackageInfo(
                "samplePackage", 1, "1", 1);
        when(packageManager.getPackageInfo("samplePackage", 0)).
                thenReturn(packageInfoBeforeInstall);

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0)).thenReturn(packageInfoAfterInstall);

        new Thread(() -> {
            try {
                androidBasePackageManager.installAPK(apkPath.toString(), "samplePackage", true, logger);
                Thread.sleep(100);
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        verify(androidBasePackageManager, timeout(1000).times(1)).uninstallPackage(eq("samplePackage"), any());
        verify(packageInstaller, timeout(1000).times(1)).uninstall(eq("samplePackage"), any());
    }

    @Test
    void GIVEN_package_installed_WHEN_reinstall_with_updated_version_THEN_install_intent_activity_started()
            throws Exception {
        AndroidBasePackageManager androidBasePackageManager = spy(new AndroidBasePackageManager(contextProvider));

        PackageInfo packageInfoBeforeInstall = createPackageInfo(
                "samplePackage", 1, "1", 0);
        PackageInfo packageInfoAfterInstall = createPackageInfo(
                "samplePackage", 2, "2", 1);
        when(packageManager.getPackageInfo("samplePackage", 0)).
                thenReturn(packageInfoBeforeInstall).
                thenReturn(packageInfoAfterInstall);

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0)).thenReturn(packageInfoAfterInstall);
        androidBasePackageManager.installAPK(apkPath.toString(), "samplePackage", true, logger);
        ArgumentCaptor<Intent> argument = ArgumentCaptor.forClass(Intent.class);
        verify(context, atLeastOnce()).startActivity(argument.capture());
        assertEquals(ACTION_VIEW, argument.getValue().getAction());
    }
}
