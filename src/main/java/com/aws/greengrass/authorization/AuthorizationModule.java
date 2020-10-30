/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.util.Utils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static com.aws.greengrass.authorization.AuthorizationHandler.ANY_REGEX;

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

    /**
     * Get resources for combination of destination, principal and operation.
     * Also returns resources covered by permissions with * operation/principal.
     *
     * @param destination destination
     * @param principal   principal (cannot be *)
     * @param operation   operation (cannot be *)
     * @return list of allowed resources
     * @throws AuthorizationException when arguments are invalid
     */
    public List<String> getResources(final String destination, String principal, String operation)
            throws AuthorizationException {
        if (Utils.isEmpty(destination) || Utils.isEmpty(principal) || Utils.isEmpty(operation)
                || principal.equals(ANY_REGEX) || operation.equals(ANY_REGEX)) {
            throw new AuthorizationException("Invalid arguments");
        }

        List<String> resources = Collections.emptyList();
        List<Permission> permissionsForDest = permissions.get(destination);
        if (!Utils.isEmpty(permissionsForDest)) {
            resources = permissionsForDest.stream()
                    .filter(p -> filterPermissionByPrincipalAndOp(p, principal, operation))
                    .map(Permission::getResource)
                    .distinct()
                    .collect(Collectors.toList());
        }

        return resources;
    }

    private boolean filterPermissionByPrincipalAndOp(Permission permission, String principal, String operation) {
        return (permission.getPrincipal().equals(ANY_REGEX) || permission.getPrincipal().equals(principal))
                && (permission.getOperation().equals(ANY_REGEX) || permission.getOperation().equals(operation));
    }
}
