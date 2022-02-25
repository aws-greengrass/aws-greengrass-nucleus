/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.aws.greengrass.android.provision.AutoStartDataStore;
import com.aws.greengrass.android.provision.BaseProvisionManager;
import com.aws.greengrass.android.provision.ProvisionManager;

import static android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED;

public class ReviveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        ProvisionManager provisionManager = new BaseProvisionManager();
        if (ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())
                && provisionManager.isProvisioned(context)
                && AutoStartDataStore.get(context)
        ) {
            NucleusForegroundService.launch(context, null);
        }
    }
}
