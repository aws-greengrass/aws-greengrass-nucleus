/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
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

import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class})
public class AndroidBaseApkManagerTest {
    private static Logger logger = LogManager.getLogger(AndroidBaseApkManagerTest.class);

    Context context;
    File tempFileDir;

    @Mock
    AndroidContextProvider contextProvider;

    @Mock(lenient = true)
    PackageManager packageManager;

    @Mock
    PackageInstaller packageInstaller;

    @Mock
    PackageInstaller.Session session;

    final String packageName = "samplePackage";
    final int sessionId = 200;

    boolean installed = false;

    PackageInfo createPackageInfo(String name, long version, String versionName, long lastUpdateTime) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = name;
        packageInfo.setLongVersionCode(version);
        packageInfo.versionName = versionName;
        packageInfo.lastUpdateTime = lastUpdateTime;
        return packageInfo;
    }

    @BeforeEach
    public void setup() throws NoSuchMethodException, IOException {
        context = spy(ApplicationProvider.getApplicationContext());
        tempFileDir = Paths.get(context.getFilesDir().toString(),
                "greengrass/v2/packages/artifacts-unarchived").toFile();
        tempFileDir.mkdirs();
        when(contextProvider.getContext()).thenReturn(context);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(packageManager.getPackageInstaller()).thenReturn(packageInstaller);
        lenient().when(packageInstaller.createSession(any())).thenReturn(sessionId);
        lenient().when(packageInstaller.openSession(sessionId)).thenReturn(session);
    }

    @Test
    void GIVEN_package_not_installed_WHEN_get_package_info_THEN_package_info_is_null() throws Exception {
        AndroidBaseApkManager androidBaseApkManager = spy(new AndroidBaseApkManager(contextProvider));
        when(packageManager.getPackageInfo(packageName, 0))
                .thenThrow(new PackageManager.NameNotFoundException());
        assertNull(androidBaseApkManager.getPackageInfo(packageName));
    }

    @Test
    void GIVEN_package_not_installed_WHEN_install_THEN_packageInstaller_is_called_ok() throws Exception {
        AndroidBaseApkManager androidBaseApkManager = spy(new AndroidBaseApkManager(contextProvider));

        // Verify package is not installed
        when(packageManager.getPackageInfo(packageName, 0)).
                thenThrow(new PackageManager.NameNotFoundException());
        assertNull(androidBaseApkManager.getPackageInfo(packageName));

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        PackageInfo packageInfo = createPackageInfo(packageName, 1, "1", 0);
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0)).thenReturn(packageInfo);

        installed = false;
        Thread thread = new Thread(() -> {
            try {
                androidBaseApkManager.installAPK(apkPath.toString(), packageName, false, logger);
                installed = true;
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();

        // Capture IntentSender from session.commit()
        ArgumentCaptor<IntentSender> argument = ArgumentCaptor.forClass(IntentSender.class);
        verify(session, timeout(1000).atLeastOnce()).commit(argument.capture());
        IntentSender statusReceiver = argument.getValue();
        assertNotNull(statusReceiver);

        // sent PendingIntent manually to the AndroidBaseApkManager
        statusReceiver.sendIntent(null, PackageInstaller.STATUS_SUCCESS, null, null, null);
        // wait some time to process intent
        Thread.sleep(1000);

        // verify is APK was "installed" (no exceptions from installAPK())
        assertEquals(true, installed);

        // verify all expected methods are called
        verify(packageInstaller, times(1)).createSession(any(PackageInstaller.SessionParams.class));
        verify(packageInstaller, times(1)).openSession(sessionId);

        verify(session, times(1)).openWrite(packageName, 0, 0);
        // .commit() is already checked
        verify(session, never()).abandon();

        thread.interrupt();
        thread.join();
    }

    @Test
    void GIVEN_package_installed_WHEN_install_same_package_THEN_installation_skipped() throws Exception {
        AndroidBaseApkManager androidBaseApkManager = spy(new AndroidBaseApkManager(contextProvider));

        final PackageInfo packageInfo = createPackageInfo(packageName, 1, "1", 0);
        when(packageManager.getPackageInfo(packageName, 0)).thenReturn(packageInfo);

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0)).thenReturn(packageInfo);

        installed = false;
        Thread thread = new Thread(() -> {
            try {
                androidBaseApkManager.installAPK(apkPath.toString(), packageName, false, logger);
                installed = true;
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        Thread.sleep(1000);

        // verify is APK was "installed" (no exceptions from installAPK())
        assertEquals(true, installed);

        // verify all expected methods are called
        verify(packageInstaller, never()).createSession(any(PackageInstaller.SessionParams.class));
        verify(packageInstaller, never()).openSession(sessionId);

        verify(session, never()).openWrite(packageName, 0, 0);
        verify(session, never()).commit(any(IntentSender.class));
        verify(session, never()).abandon();

        thread.interrupt();
        thread.join();
    }

    @Test
    // TODO: android: Fix crashing CI. https://klika-tech.atlassian.net/browse/GGSA-252
    void GIVEN_package_installed_with_higher_version_WHEN_force_reinstall_THEN_uninstall_called()
            throws Exception {
        AndroidBaseApkManager androidBaseApkManager = spy(new AndroidBaseApkManager(contextProvider));

        PackageInfo packageInfoBeforeInstall = createPackageInfo(
                packageName, 2, "2", 0);
        PackageInfo packageInfoAfterInstall = createPackageInfo(
                packageName, 1, "1", 1);
        when(packageManager.getPackageInfo(packageName, 0)).thenReturn(packageInfoBeforeInstall);

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0))
                .thenReturn(packageInfoAfterInstall);

        installed = false;
        Thread thread = new Thread(() -> {
            try {
                androidBaseApkManager.installAPK(apkPath.toString(), packageName, true, logger);
                installed = true;
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();

        // Capture IntentSender from packageInstaller.uninstall()
        ArgumentCaptor<IntentSender> argument = ArgumentCaptor.forClass(IntentSender.class);
        verify(packageInstaller, timeout(1000).atLeastOnce()).uninstall(eq(packageName), argument.capture());
        IntentSender statusReceiver = argument.getValue();
        assertNotNull(statusReceiver);

        // sent PendingIntent manually to the AndroidBaseApkManager
        statusReceiver.sendIntent(null, PackageInstaller.STATUS_SUCCESS, null, null, null);
        // wait some time to process uninstall result intent


        // Capture IntentSender from session.commit()
        ArgumentCaptor<IntentSender> argument2 = ArgumentCaptor.forClass(IntentSender.class);
        verify(session, timeout(1000).atLeastOnce()).commit(argument2.capture());
        IntentSender statusReceiver2 = argument2.getValue();
        assertNotNull(statusReceiver2);

        // sent PendingIntent manually to the AndroidBaseApkManager
        statusReceiver2.sendIntent(null, PackageInstaller.STATUS_SUCCESS, null, null, null);
        // wait some time to process intent
        Thread.sleep(1000);

        // verify is APK was "installed" (no exceptions from installAPK())
        assertEquals(true, installed);

        // verify all expected methods are called for uninstall
        verify(androidBaseApkManager, times(1)).uninstallPackage(eq(packageName), any());

        // verify all expected methods are called for install
        verify(packageInstaller, times(1)).createSession(any(PackageInstaller.SessionParams.class));
        verify(packageInstaller, times(1)).openSession(sessionId);

        verify(session, times(1)).openWrite(packageName, 0, 0);
        // .commit() is already checked
        verify(session, never()).abandon();

        thread.interrupt();
        thread.join();
    }

    @Test
    void GIVEN_package_installed_WHEN_reinstall_with_updated_version_THEN_packageInstaller_is_called_ok()
            throws Exception {
        AndroidBaseApkManager androidBaseApkManager = spy(new AndroidBaseApkManager(contextProvider));

        PackageInfo packageInfoBeforeInstall = createPackageInfo(
                packageName, 1, "1", 0);
        PackageInfo packageInfoAfterInstall = createPackageInfo(
                packageName, 2, "2", 1);
        when(packageManager.getPackageInfo(packageName, 0)).
                thenReturn(packageInfoBeforeInstall).
                thenReturn(packageInfoAfterInstall);

        Path apkPath = Paths.get(tempFileDir.toString(), "samplePackage.apk");
        apkPath.toFile().createNewFile();
        when(packageManager.getPackageArchiveInfo(apkPath.toString(), 0)).thenReturn(packageInfoAfterInstall);


        installed = false;
        Thread thread = new Thread(() -> {
            try {
                androidBaseApkManager.installAPK(apkPath.toString(), packageName, false, logger);
                installed = true;
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();

        // Capture IntentSender from session.commit()
        ArgumentCaptor<IntentSender> argument = ArgumentCaptor.forClass(IntentSender.class);
        verify(session, timeout(1000).atLeastOnce()).commit(argument.capture());
        IntentSender statusReceiver = argument.getValue();
        assertNotNull(statusReceiver);

        // sent PendingIntent manually to the AndroidBaseApkManager
        statusReceiver.sendIntent(null, PackageInstaller.STATUS_SUCCESS, null, null, null);
        // wait some time to process intent
        Thread.sleep(1000);

        // verify is APK was "installed" (no exceptions from installAPK())
        assertEquals(true, installed);

        // verify all expected methods are called
        verify(packageInstaller, times(1)).createSession(any(PackageInstaller.SessionParams.class));
        verify(packageInstaller, times(1)).openSession(sessionId);

        verify(session, times(1)).openWrite(packageName, 0, 0);
        // .commit() is already checked
        verify(session, never()).abandon();

        thread.interrupt();
        thread.join();
    }

    @Test
    void GIVEN_package_installed_WHEN_uninstall_THEN_uninstall_called()
            throws Exception {
        AndroidBaseApkManager androidBaseApkManager = spy(new AndroidBaseApkManager(contextProvider));

        final PackageInfo packageInfo = createPackageInfo(packageName, 1, "1", 0);
        when(packageManager.getPackageInfo(packageName, 0)).thenReturn(packageInfo);

        installed = true;
        Thread thread = new Thread(() -> {
            try {
                androidBaseApkManager.uninstallPackage(packageName, logger);
                installed = false;
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();

        // Capture IntentSender from packageInstaller.uninstall()
        ArgumentCaptor<IntentSender> argument = ArgumentCaptor.forClass(IntentSender.class);
        verify(packageInstaller, timeout(1000).atLeastOnce()).uninstall(eq(packageName), argument.capture());
        IntentSender statusReceiver = argument.getValue();
        assertNotNull(statusReceiver);

        // sent PendingIntent manually to the AndroidBaseApkManager
        statusReceiver.sendIntent(null, PackageInstaller.STATUS_SUCCESS, null, null, null);
        // wait some time to process uninstall result intent
        // wait some time to process intent
        Thread.sleep(1000);

        // verify is APK was "uninstalled" (no exceptions from uninstallPackage())
        assertEquals(false, installed);

        thread.interrupt();
        thread.join();
    }
}
