/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthZException;
import com.aws.iot.evergreen.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple permission table which stores permissions. A permission is a
 * 4 value set of destination,source,operation,resource.
 */
public class AuthZModule {
    ConcurrentHashMap<String, List<Permission>> permissions;

    AuthZModule() {
        permissions = new ConcurrentHashMap<>();
    }

    /**
     * Add permission for the given input set.
     * @param destination destination entity
     * @param permission set of source, operation, resource.
     * @throws AuthZException when arguments are invalid
     */
    public void addPermission(final String destination, Permission permission) throws AuthZException {
        // resource is allowed to be null
        if (Utils.isEmpty(permission.getSource())
                || Utils.isEmpty(destination)
                || Utils.isEmpty(permission.getOperation())) {
            throw new AuthZException("Either one parameter is empty");
        }
        // resource as null is ok, but it should not be empty
        String resource = permission.getResource();
        if (resource != null && Utils.isEmpty(resource)) {
            throw new AuthZException("Resource cannot be empty");
        }
        permissions.computeIfAbsent(destination, a -> new ArrayList<>()).add(permission);
    }

    /**
     * Check if the combination of destination,source,operation,resource exists in the table.
     * @param destination destination value
     * @param permission set of source, operation and resource.
     * @return true if the input combination is present.
     * @throws AuthZException when arguments are invalid
     */
    public boolean isPresent(final String destination, Permission permission) throws AuthZException {
        if (Utils.isEmpty(permission.getSource())
                || Utils.isEmpty(destination)
                || Utils.isEmpty(permission.getOperation())) {
            throw new AuthZException("Either one parameter is empty");
        }
        if (!permissions.containsKey(destination)) {
            return false;
        }
        List<Permission> permissionsForDest = permissions.get(destination);
        return permissionsForDest.contains(permission);
    }
}
