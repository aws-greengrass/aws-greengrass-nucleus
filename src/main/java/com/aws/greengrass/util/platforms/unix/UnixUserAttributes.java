/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.util.platforms.UserPlatform;
import lombok.Builder;
import lombok.Value;

import java.util.Optional;


/**
 * Unix specific user attributes.
 */
@Value
@Builder
public class UnixUserAttributes implements UserPlatform.UserAttributes {
    Long primaryGid;
    String principalName;
    String principalIdentifier;

    /**
     * Get the UID.
     * @return the numeric user id.
     */
    public long getUID() {
        // use long since it is generally an unsigned integer (although some OSs may use -2 for users like "nobody")
        return Long.parseLong(getPrincipalIdentifier());
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
