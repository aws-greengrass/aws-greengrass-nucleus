/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

/**
 * Interface provided by Android Application layer like package manager, user attributes.
 * Actually join many interfaces in one.
 */
public interface AndroidServiceLevelAPI extends AndroidUserId, AndroidComponentManager, AndroidPackageManager {
    /**
     * Terminates service
     * @param status exit status
     */
    public void terminate(int status);
}
