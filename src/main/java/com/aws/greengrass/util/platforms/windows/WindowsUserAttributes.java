/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.util.platforms.UserPlatform;
import lombok.Builder;
import lombok.Value;

/**
 * Windows specific user attributes.
 */
@Value
@Builder
public class WindowsUserAttributes implements UserPlatform.UserAttributes {
    String principalName;
    String principalIdentifier;

    @Override
    public boolean isSuperUser() {
        // TODO: It seems on Windows, I can only check if the *current* user is an administrator or not. I cannot check
        // any given user. We may need to change the platform abstraction to only check for the current user.
        return false;
    }
}
