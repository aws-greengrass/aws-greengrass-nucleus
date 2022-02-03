/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

#if ANDROID
package com.aws.greengrass.android.managers;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static com.aws.greengrass.android.service.NucleusForegroundService.STOP_NUCLEUS;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.aws.greengrass.nucleus.R;

public class NotManager {

    private static final String CHANNEL_ID = "NUCLEUS_CHANNEL_ID";
    private static final Integer ACTIVITY_NOT_ID = 3945;
    public static final Integer SERVICE_NOT_ID = 49375;

    public static void notForActivityComponent(Context context, Intent intent, String title) {
        PendingIntent contentIntent = PendingIntent.getActivity(context,
                0,
                intent,
                FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
        Notification not = new NotificationCompat.Builder(context, createChannel(context))
                .setContentTitle(title)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setSmallIcon(android.R.drawable.ic_delete)
                .build();
        NotificationManager notificationManager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(ACTIVITY_NOT_ID, not);
    }

    public static Notification notForService(Context context,
                                             String title) {
        PendingIntent contentIntent = PendingIntent.getBroadcast(
                context,
                0,
                new Intent(STOP_NUCLEUS),
                FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, createChannel(context))
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.ic_secure)
                .addAction(android.R.drawable.ic_secure, context.getString(R.string.exit), contentIntent)
                .build();
    }

    private static String createChannel(Context context) {
        android.app.NotificationManager mNotificationManager =
                (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel mChannel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.not_channel_name),
                IMPORTANCE_LOW);
        mNotificationManager.createNotificationChannel(mChannel);
        return CHANNEL_ID;
    }
}
#endif