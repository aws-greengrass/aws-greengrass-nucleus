/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.aws.greengrass.android.provision.AutoStartDataStore;
import com.aws.greengrass.android.provision.BaseProvisionManager;
import com.aws.greengrass.android.provision.ProvisionManager;

import static android.content.Intent.ACTION_BOOT_COMPLETED;

public class ReviveReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void onReceive(Context context, Intent intent) {
        try {
            if (ACTION_BOOT_COMPLETED.equals(intent.getAction())
                    && AutoStartDataStore.get(context)) {
                ProvisionManager provisionManager =
                        BaseProvisionManager.getInstance(context.getFilesDir());
                if (provisionManager.isProvisioned()) {
                    DefaultGreengrassComponentService.launch(context.getApplicationContext());
                }
            }
        } catch (Exception e) {
            Log.e("ReviveReceiver", e.toString());
        }
    }
}
