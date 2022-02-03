/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import android.app.ActivityManager;
import android.content.Context;

import com.aws.greengrass.android.utils.NucleusContentProvider;
import com.aws.greengrass.util.platforms.UserPlatform;

import java.util.Optional;

import lombok.Builder;
import lombok.Value;


// FIXME: android: to be implemented
/**
 * Android specific user attributes.
 */
@Value
@Builder
public class AndroidUserAttributes implements UserPlatform.UserAttributes {
    Long primaryGid;
    String principalName;
    String principalIdentifier;
    Context context;

    private long getUID() {
        ActivityManager activityManager = (ActivityManager) NucleusContentProvider.getApp().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningAppProcessInfo pInfo = activityManager.getRunningAppProcesses().get(0);
        return pInfo.uid;
    }

    /**
     * Get the primary GID if present for the user. If the user does not actually exist on the device, an empty
     * optional is returned.
     *
     * @return the group id of the users primary group or empty if the user is not a known user on the device.
     */
    public Optional<Long> getPrimaryGID() {
        return Optional.ofNullable(primaryGid);
    }


    @Override
    public boolean isSuperUser() {
        return getUID() == 0;
    }
}
