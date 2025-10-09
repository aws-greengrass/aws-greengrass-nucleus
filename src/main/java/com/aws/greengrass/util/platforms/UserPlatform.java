/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import java.io.IOException;

/**
 * Interface for user and group related lookups.
 */
public interface UserPlatform {

    /**
     * Attributes about a user or group.
     */
    interface BasicAttributes {
        /**
         * Get the unique identifier for the resource.
         *
         * @return the unique identifier for the resource.
         */
        String getPrincipalIdentifier();

        /**
         * Get the name of the resource.
         *
         * @return the name of the resource.
         */
        String getPrincipalName();
    }

    /**
     * Attributes about a user.
     */
    interface UserAttributes extends BasicAttributes {
        /**
         * Answer if the user is a super user or not.
         *
         * @return true if the user has special privileges.
         */
        boolean isSuperUser();
    }

    /**
     * Check if a user exists.
     * 
     * @param user the username to check
     * @return True if the user exists. False otherwise.
     */
    boolean userExists(String user);

    /**
     * Lookup a group by a group identifier. This could be a guid or integer string.
     * 
     * @param group the name of the group.
     * @return the user
     * @throws IOException if the group cannot be found.
     */
    BasicAttributes lookupGroupByName(String group) throws IOException;

    /**
     * Lookup a group by a grop identifier. This could be a guid or integer string.
     * 
     * @param identifier an identifier.
     * @return the group
     * @throws IOException if the group cannot be found.
     */
    BasicAttributes lookupGroupByIdentifier(String identifier) throws IOException;

    /**
     * Lookup the user executing the nucleus.
     * 
     * @return the user.
     * @throws IOException if an error occurs loading the user information.
     */
    UserAttributes lookupCurrentUser() throws IOException;
}
