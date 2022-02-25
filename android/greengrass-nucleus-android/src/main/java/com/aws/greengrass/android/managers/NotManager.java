/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import com.aws.greengrass.nucleus.R;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.Context.NOTIFICATION_SERVICE;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_STOP_COMPONENT;

/**
 * Notification Manager.
 */
public class NotManager {

    private static final String CHANNEL_ID = "NUCLEUS_CHANNEL_ID";
    private static final Integer ACTIVITY_NOT_ID = 3945;
    public static final Integer SERVICE_NOT_ID = 49375;

    /**
     * Send notification to Activity component.
     *
     * @param context application Context
     * @param intent  Intent to send
     * @param title content title
     */
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
                .getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(ACTIVITY_NOT_ID, not);
    }

    /**
     * Get new notification.
     *
     * @param context application Context
     * @param title content title
     * @return new notification object.
     */
    public static Notification notForService(Context context,
                                             String title) {
        PendingIntent contentIntent = PendingIntent.getBroadcast(
                context,
                0,
                new Intent(ACTION_STOP_COMPONENT), FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, createChannel(context))
                .setContentTitle(title)
                .setSmallIcon(R.drawable.ic_greengrass)
                .addAction(R.drawable.ic_greengrass, context.getString(R.string.exit), contentIntent)
                .build();
    }

    public static boolean isNucleusNotExist(Context context) {
        NotificationManager mNotificationManager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        StatusBarNotification[] notifications = mNotificationManager.getActiveNotifications();
        for (StatusBarNotification notification : notifications) {
            if (notification.getId() == SERVICE_NOT_ID) {
                return true;
            }
        }
        return false;
    }

    private static String createChannel(Context context) {
        android.app.NotificationManager notificationManager =
                (android.app.NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel notificationChannel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.not_channel_name),
                IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(notificationChannel);
        return CHANNEL_ID;
    }
}
