/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.util.platforms.UserPlatform;
import lombok.Builder;
import lombok.Value;

// FIXME: android: to be implemented
/**
 * Android specific group attributes.
 */
@Value
@Builder
public class AndroidGroupAttributes implements UserPlatform.BasicAttributes {
    String principalName;
    String principalIdentifier;

    /**
     * Get the GID of this group.
     *
     * @return the numeric group identifier.
     */
    public int getGID() {
        return Integer.parseInt(getPrincipalIdentifier());
    }

}
