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

public class AuthorizationModule {
    // Destination, Principal, Operation, Resource
    Map<String, Map<String, Map<String, WildcardVariableTrie>>> amazingMap = new DefaultConcurrentHashMap<>(
            () -> new DefaultConcurrentHashMap<>(() -> new DefaultConcurrentHashMap<>(WildcardVariableTrie::new)));
    Map<String, Map<String, Map<String, Set<String>>>> rawResourceList = new DefaultConcurrentHashMap<>(
            () -> new DefaultConcurrentHashMap<>(() -> new DefaultConcurrentHashMap<>(CopyOnWriteArraySet::new)));

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

    public void deletePermissionsWithDestination(String destination) {
        amazingMap.remove(destination);
        rawResourceList.remove(destination);
    }

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
        if (amazingMap.containsKey(destination)) {
            Map<String, Map<String, WildcardVariableTrie>> destMap = amazingMap.get(destination);
            if (destMap.containsKey(permission.getPrincipal())) {
                Map<String, WildcardVariableTrie> principalMap = destMap.get(permission.getPrincipal());
                if (principalMap.containsKey(permission.getOperation())) {
                    return principalMap.get(permission.getOperation()).matches(permission.getResource(), variables);
                }
            }
        }
        return false;
    }

    public Set<String> getResources(String destination, String principal, String operation)
            throws AuthorizationException {
        if (Utils.isEmpty(destination) || Utils.isEmpty(principal) || Utils.isEmpty(operation) || principal
                .equals(ANY_REGEX) || operation.equals(ANY_REGEX)) {
            throw new AuthorizationException("Invalid arguments");
        }

        HashSet<String> out = new HashSet<>();
        getResourceInternal(out, destination, principal, operation);
        getResourceInternal(out, destination, ANY_REGEX, operation);
        getResourceInternal(out, destination, principal, ANY_REGEX);

        return out;
    }

    private void getResourceInternal(Set<String> out, String destination, String principal, String operation) {
        if (rawResourceList.containsKey(destination)) {
            Map<String, Map<String, Set<String>>> destMap = rawResourceList.get(destination);
            if (destMap.containsKey(principal)) {
                Map<String, Set<String>> principalMap = destMap.get(principal);
                if (principalMap.containsKey(operation)) {
                    out.addAll(principalMap.get(operation));
                }
            }
        }
    }
}
