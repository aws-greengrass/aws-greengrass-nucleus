package com.aws.greengrass.android.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import static android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED;

public class ReviveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            NucleusForegroundService.launch(context, null);
        }
    }
}
