/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.util.Utils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple permission table which stores permissions. A permission is a
 * 4 value set of destination,principal,operation,resource.
 */
public class AuthorizationModule {
    ConcurrentHashMap<String, Set<Permission>> permissions = new ConcurrentHashMap<>();

    /**
     * Add permission for the given input set.
     * @param destination destination entity
     * @param permission set of principal, operation, resource.
     * @throws AuthorizationException when arguments are invalid
     */
    public void addPermission(final String destination, Permission permission) throws AuthorizationException {
        // resource is allowed to be null
        if (Utils.isEmpty(permission.getPrincipal())
                || Utils.isEmpty(destination)
                || Utils.isEmpty(permission.getOperation())) {
            throw new AuthorizationException("Invalid arguments");
        }
        // resource as null is ok, but it should not be empty
        String resource = permission.getResource();
        if (resource != null && Utils.isEmpty(resource)) {
            throw new AuthorizationException("Resource cannot be empty");
        }
        permissions.computeIfAbsent(destination, a -> new HashSet<>()).add(permission);
    }

    /**
     * Check if the combination of destination,principal,operation,resource exists in the table.
     * @param destination destination value
     * @param permission set of principal, operation and resource.
     * @return true if the input combination is present.
     * @throws AuthorizationException when arguments are invalid
     */
    public boolean isPresent(final String destination, Permission permission) throws AuthorizationException {
        if (Utils.isEmpty(permission.getPrincipal())
                || Utils.isEmpty(destination)
                || Utils.isEmpty(permission.getOperation())) {
            throw new AuthorizationException("Invalid arguments");
        }
        // resource as null is ok, but it should not be empty
        String resource = permission.getResource();
        if (resource != null && Utils.isEmpty(resource)) {
            throw new AuthorizationException("Resource cannot be empty");
        }
        if (!permissions.containsKey(destination)) {
            return false;
        }
        Set<Permission> permissionsForDest = permissions.get(destination);
        return permissionsForDest.contains(permission);
    }
}
