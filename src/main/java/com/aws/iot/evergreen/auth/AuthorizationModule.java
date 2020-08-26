/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.util.Utils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple permission table which stores permissions. A permission is a
 * 4 value set of destination,principal,operation,resource.
 */
public class AuthorizationModule {
    //These Lists are initialized as CopyOnWriteArrayList and so should be thread-safe.
    ConcurrentHashMap<String, List<Permission>> permissions = new ConcurrentHashMap<>();

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
        permissions.computeIfAbsent(destination, a -> new CopyOnWriteArrayList<>()).add(permission);
    }

    /**
     * Clear the permission list for a given destination. This is used when updating policies for a component.
     * @param destination destination value
     */
    public void deletePermissionsWithDestination(String destination) {
        if (permissions.containsKey(destination) && !Utils.isEmpty(permissions.get(destination))) {
            permissions.get(destination).clear();
        }
    }

    /**
     * Clear the permission list with a given principal.
     *
     * @param component component name
     */
    public void deletePermissionsWithPrincipal(String component) {
        for (Map.Entry<String, List<Permission>> permissionSet : permissions.entrySet()) {
            permissionSet.getValue().removeIf(entry -> entry.hasPrincipal(component));
        }
    }

    /**
     * Clear permissions for a given component. This is used when a component is removed.
     *
     * @param component component name
     */
    public void clearPermissions(String component) {
        deletePermissionsWithPrincipal(component);
        deletePermissionsWithDestination(component);
        permissions.remove(component);
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
        List<Permission> permissionsForDest = permissions.get(destination);
        if (!Utils.isEmpty(permissionsForDest)) {
            return permissionsForDest.contains(permission);
        }

        return false;
    }
}
