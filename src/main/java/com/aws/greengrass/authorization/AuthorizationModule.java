/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.authorization.AuthorizationHandler.ResourceLookupPolicy;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.util.DefaultConcurrentHashMap;
import com.aws.greengrass.util.Utils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.aws.greengrass.authorization.AuthorizationHandler.ANY_REGEX;
import static com.aws.greengrass.authorization.WildcardTrie.escapeChar;
import static com.aws.greengrass.authorization.WildcardTrie.getActualChar;
import static com.aws.greengrass.authorization.WildcardTrie.nullChar;
import static com.aws.greengrass.authorization.WildcardTrie.singleCharWildcard;
import static com.aws.greengrass.authorization.WildcardTrie.wildcardChar;

/**
 * Simple permission table which stores permissions. A permission is a
 * 4 value set of destination,principal,operation,resource.
 */
public class AuthorizationModule {
    // Destination, Principal, Operation, Resource
    Map<String, Map<String, Map<String, WildcardTrie>>> resourceAuthZCompleteMap =
            new DefaultConcurrentHashMap<>(() -> new DefaultConcurrentHashMap<>(() ->
                    new DefaultConcurrentHashMap<>(WildcardTrie::new)));
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
        String resource = permission.getResource();
        validateResource(resource);
        resourceAuthZCompleteMap.get(destination).get(permission.getPrincipal()).get(permission.getOperation()).add(
                resource);
        rawResourceList.get(destination).get(permission.getPrincipal()).get(permission.getOperation()).add(
                resource);
    }

    /**
     * Only allow '?' if it's escaped. You can only escape special characters ('*', '$', '?').
     * Any occurrence of '${' is only valid if it holds a single valid special character ('*', '$', '?') inside it
     * and ends with '}'. (eg: "${*}" is valid, "${c}" is invalid, "${c" is invalid, ${*bc} is invalid)
     * @param resource resource to be validated
     */
    private void validateResource(String resource) throws AuthorizationException {
        if (resource == null) {
            return;
        }
        // resource as null is ok, but it should not be empty
        if (Utils.isEmpty(resource)) {
            throw new AuthorizationException("Resource cannot be empty");
        }
        int length = resource.length();
        for (int i = 0; i < length; i++) {
            char currentChar = resource.charAt(i);
            if (currentChar == escapeChar && i + 1 < length && resource.charAt(i + 1) == '{') {
                char actualChar = getActualChar(resource.substring(i));
                if (actualChar == nullChar) {
                    throw new AuthorizationException("Resource contains an invalid escape sequence. "
                            + "You can use ${*}, ${$}, or ${?}");
                }
                if (!isSpecialChar(actualChar)) {
                    throw new AuthorizationException("Resource contains an invalid escape "
                            + "sequence: ${" + actualChar + "}. You can use ${*}, ${$}, or ${?}");
                }
                // skip next 3 characters as they are accounted for in escape sequence
                i = i + 3;
            }
            if (currentChar == singleCharWildcard) {
                throw new AuthorizationException("Resource contains invalid character: '?'. "
                        + "Use an escape sequence: ${?}. The '?' character isn't supported as a wildcard");
            }
        }
    }

    boolean isSpecialChar(char actualChar) {
        return actualChar == wildcardChar || actualChar == escapeChar || actualChar == singleCharWildcard;
    }


    /**
     * Clear the permission list for a given destination. This is used when updating policies for a component.
     * @param destination destination value
     */
    public void deletePermissionsWithDestination(String destination) {
        resourceAuthZCompleteMap.remove(destination);
        rawResourceList.remove(destination);
    }

    /**
     * Check if the combination of destination,principal,operation,resource exists in the table.
     * @param destination destination value
     * @param permission set of principal, operation and resource.
     * @param resourceLookupPolicy whether to match MQTT wildcards or not.
     * @return true if the input combination is present.
     * @throws AuthorizationException when arguments are invalid
     */
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    public boolean isPresent(String destination, Permission permission, ResourceLookupPolicy resourceLookupPolicy)
            throws AuthorizationException {
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
        if (resourceAuthZCompleteMap.containsKey(destination)) {
            Map<String, Map<String, WildcardTrie>> destMap = resourceAuthZCompleteMap.get(destination);
            if (destMap.containsKey(permission.getPrincipal())) {
                Map<String, WildcardTrie> principalMap = destMap.get(permission.getPrincipal());
                if (principalMap.containsKey(permission.getOperation())) {
                    return principalMap.get(permission.getOperation()).matches(permission.getResource(),
                            resourceLookupPolicy);
                }
            }
        }
        return false;
    }

    public boolean isPresent(String destination, Permission permission) throws AuthorizationException {
        return isPresent(destination, permission, ResourceLookupPolicy.STANDARD);
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

    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    private void addResourceInternal(Set<String> out, String destination, String principal, String operation) {
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
