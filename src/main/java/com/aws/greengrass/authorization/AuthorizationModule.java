/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.util.DefaultConcurrentHashMap;
import com.aws.greengrass.util.Utils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.aws.greengrass.authorization.AuthorizationHandler.ANY_REGEX;

/**
 * Simple permission table which stores permissions. A permission is a
 * 4 value set of destination,principal,operation,resource.
 */
public class AuthorizationModule {
    // Destination, Principal, Operation, Resource
    Map<String, Map<String, Map<String, WildcardVariableTrie>>> amazingMap = new DefaultConcurrentHashMap<>(
            () -> new DefaultConcurrentHashMap<>(() -> new DefaultConcurrentHashMap<>(WildcardVariableTrie::new)));
    Map<String, Map<String, Map<String, Set<String>>>> rawResourceList = new DefaultConcurrentHashMap<>(
            () -> new DefaultConcurrentHashMap<>(() -> new DefaultConcurrentHashMap<>(CopyOnWriteArraySet::new)));

    /**
     * Add permission for the given input set.
     * @param destination destination entity
     * @param permission set of principal, operation, resource.
     * @throws AuthorizationException when arguments are invalid
     */
    public void addPermission(String destination, Permission permission) throws AuthorizationException {
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
        amazingMap.get(destination).get(permission.getPrincipal()).get(permission.getOperation()).add(
                permission.getResource());
        rawResourceList.get(destination).get(permission.getPrincipal()).get(permission.getOperation()).add(
                permission.getResource());
    }

    /**
     * Clear the permission list for a given destination. This is used when updating policies for a component.
     * @param destination destination value
     */
    public void deletePermissionsWithDestination(String destination) {
        amazingMap.remove(destination);
        rawResourceList.remove(destination);
    }

    /**
     * Check if the combination of destination,principal,operation,resource exists in the table.
     * @param destination destination value
     * @param permission set of principal, operation and resource.
     * @param variables variable set
     * @return true if the input combination is present.
     * @throws AuthorizationException when arguments are invalid
     */
    public boolean isPresent(String destination, Permission permission, Map<String,
            String> variables) throws AuthorizationException {
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
        Map<String, Map<String, WildcardVariableTrie>> destMap = amazingMap.get(destination);
        if (destMap != null) {
            Map<String, WildcardVariableTrie> principalMap = destMap.get(permission.getPrincipal());
            if (principalMap != null && principalMap.containsKey(permission.getOperation())) {
                return principalMap.get(permission.getOperation()).matches(permission.getResource(), variables);
            }
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
    public Set<String> getResources(String destination, String principal, String operation)
            throws AuthorizationException {
        if (Utils.isEmpty(destination) || Utils.isEmpty(principal) || Utils.isEmpty(operation) || principal
                .equals(ANY_REGEX) || operation.equals(ANY_REGEX)) {
            throw new AuthorizationException("Invalid arguments");
        }

        HashSet<String> out = new HashSet<>();
        addResourceInternal(out, destination, principal, operation);
        addResourceInternal(out, destination, ANY_REGEX, operation);
        addResourceInternal(out, destination, principal, ANY_REGEX);

        return out;
    }

    private void addResourceInternal(Set<String> out, String destination, String principal, String operation) {
        Map<String, Map<String, Set<String>>> destMap = rawResourceList.get(destination);
        if (destMap != null) {
            Map<String, Set<String>> principalMap = destMap.get(principal);
            if (principalMap != null && principalMap.containsKey(operation)) {
                out.addAll(principalMap.get(operation));
            }
        }
    }
}
