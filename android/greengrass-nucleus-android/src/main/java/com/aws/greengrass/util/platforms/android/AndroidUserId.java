/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

/**
 * Interface to get user attributes, in full implementation it must be replaced by UserPlatform.UserAttributes, currently stub implementation is used and here is only one method.
 */
public interface AndroidUserId {

    /**
     * Get user id of current user.
     *
     * @return uid of current user.
     */
    long getUID();
}
