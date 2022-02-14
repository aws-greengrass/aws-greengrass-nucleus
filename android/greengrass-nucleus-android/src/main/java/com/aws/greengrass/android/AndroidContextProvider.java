/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android;

import android.content.Context;

/**
 * Android context getter interface.
 */
public interface AndroidContextProvider {
    /**
     * Get an Android Context.
     *
     * @return Android context object
     */
    Context getContext();
}