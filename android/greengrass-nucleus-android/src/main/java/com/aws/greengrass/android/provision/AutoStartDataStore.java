/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import android.content.Context;
import android.content.SharedPreferences;
import lombok.experimental.UtilityClass;

@UtilityClass
public class AutoStartDataStore {
    private static final String PREF = "com.aws.greengrass.nucleus.pref";
    private static final String KEY = "def_start";

    public static void set(Context context, Boolean value) {
        getPreferences(context).edit().putBoolean(KEY, value).apply();
    }

    public static boolean get(Context context) {
        return getPreferences(context).getBoolean(KEY, true);
    }

    private static
    SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
