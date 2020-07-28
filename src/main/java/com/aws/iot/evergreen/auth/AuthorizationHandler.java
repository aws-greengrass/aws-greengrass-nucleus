/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Main module which is responsible for handling AuthZ for evergreen. This only manages
 * the AuthZ configuration and performs lookups based on the config. Config is just a copy of
 * customer config and this module does not try to optimize storage. For instance,
 * if customer specifies same policies twice, we treat and store them separately. Services are
 * identified by their service identifiers (component names) and operation/resources are assumed to be
 * opaque strings. They are not treated as confidential and it should be the responsibility
 * of the caller to use proxy identifiers for confidential data. Implementation optimizes for fast lookups
 * and not for storage.
 */
@Singleton
public class AuthorizationHandler {
    private static final String ANY_REGEX = "*";
    private static final Logger logger = LogManager.getLogger(AuthorizationHandler.class);
    private final AuthorizationModule authModule;
    private final ConcurrentHashMap<String, Set<String>> serviceToOperationsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<AuthorizationPolicy>> serviceToAuthZConfig = new ConcurrentHashMap<>();
    private final Kernel kernel;

    /**
     * Constructor for AuthZ.
     * @param kernel kernel module for getting service information
     */
    @Inject
    public AuthorizationHandler(Kernel kernel) {
        authModule = new AuthorizationModule();
        this.kernel = kernel;
    }

    /**
     * Check if the combination of destination, principal, operation and resource is allowed.
     * A scenario where this method is called is for a request which originates from {@code principal}
     * service destined for {@code destination} service, which needs access to {@code resource}
     * using API {@code operation}.
     * @param destination Destination service which is being accessed.
     * @param permission container for principal, operation and resource
     * @return whether the input combination is a valid flow.
     * @throws AuthorizationException when flow is not authorized.
     */
    public boolean isAuthorized(String destination, Permission permission) throws AuthorizationException {
        String principal = permission.getPrincipal();
        String operation = permission.getOperation();
        String resource = permission.getResource();
        // If the operation is not registered with the destination service, then fail
        isOperationValid(destination, operation);

        // Lookup all possible allow configurations starting from most specific to least
        // This helps for access logs, as customer can figure out which policy is being hit.
        String[][] combinations = {
                {destination, principal, operation, resource},
                {destination, principal, operation, ANY_REGEX},
                {destination, principal, ANY_REGEX, resource},
                {destination, ANY_REGEX, operation, resource},
                {destination, principal, ANY_REGEX, ANY_REGEX},
                {destination, ANY_REGEX, operation, ANY_REGEX},
                {destination, ANY_REGEX, ANY_REGEX, resource},
                {destination, ANY_REGEX, ANY_REGEX, ANY_REGEX},
        };

        for (String[] combination: combinations) {
            if (authModule.isPresent(combination[0],
                    Permission.builder()
                            .principal(combination[1])
                            .operation(combination[2])
                            .resource(combination[3])
                            .build())) {
                logger.atDebug().log("Hit policy with principal {}, operation {}, resource {}",
                        combination[1],
                        combination[2],
                        combination[3]);
                return true;
            }
        }
        throw new AuthorizationException(
                String.format("Principal %s is not authorized to perform %s:%s on resource %s",
                        principal,
                        destination,
                        operation,
                        resource));
    }

    /**
     * Register a service with AuthZ module. This registers an evergreen service with authorization module.
     * This is required to register list of operations supported by a service especially for 3P service
     * in future, whose operations might not be known at bootstrap.
     * Operations are identifiers which the service intends to match for incoming requests by calling
     * {@link #isAuthorized(String, Permission)} isAuthorized} method.
     * @param serviceName Name of the service to be registered.
     * @param operations Set of operations the service needs to register with AuthZ.
     * @throws AuthorizationException If service is already registered.
     */
    public void registerService(String serviceName, Set<String> operations)
            throws AuthorizationException {
        if (Utils.isEmpty(operations) || Utils.isEmpty(serviceName)) {
            throw new AuthorizationException("Invalid arguments for registerService()");
        }
        if (serviceToOperationsMap.containsKey(serviceName)) {
            throw new AuthorizationException("Service already registered: " + serviceName);
        }

        operations.add(ANY_REGEX);
        Set<String> operationsCopy = Collections.unmodifiableSet(new HashSet<>(operations));
        serviceToOperationsMap.putIfAbsent(serviceName, operationsCopy);
    }

    /**
     * Loads authZ policies for future auth lookups. The policies should not have confidential
     * values. This method assumes that the service names for principal and destination,
     * the operations and resources must not be secret and can be logged or shared if required.
     * @param serviceName Destination service which intents to supply auth policies
     * @param policies policies which has list of policies. All policies are treated as separate
     *               and no merging or joins happen. Duplicated policies would result in duplicated
     *               permissions but would not impact functionality.
     * @throws AuthorizationException if there is a problem loading the policies.
     */
    public void loadAuthorizationPolicy(String serviceName, List<AuthorizationPolicy> policies)
            throws AuthorizationException {
        // TODO: Make this method atomic operation or thread safe for manipulating
        // underlying permission store.
        if (Utils.isEmpty(policies)) {
            throw new AuthorizationException("policies is null/empty");
        }
        isServiceRegistered(serviceName);
        validatePolicyId(policies);
        // First validate if all principals and operations are valid
        for (AuthorizationPolicy policy: policies) {
            validatePrincipals(policy);
            validateOperations(serviceName, policy);
        }
        // now start adding the policies as permissions
        for (AuthorizationPolicy policy: policies) {
            addPermission(serviceName, policy.getPrincipals(), policy.getOperations(), policy.getResources());
        }
        this.serviceToAuthZConfig.put(serviceName, policies);
    }

    private void isServiceRegistered(String serviceName) throws AuthorizationException {
        if (Utils.isEmpty(serviceName)) {
            throw new AuthorizationException("Service name is not specified: " + serviceName);
        }
        if (!serviceToOperationsMap.containsKey(serviceName)) {
            throw new AuthorizationException("Service not registered: " + serviceName);
        }
    }

    private void isOperationValid(String serviceName, String operation)
            throws AuthorizationException {
        isServiceRegistered(serviceName);
        if (!serviceToOperationsMap.get(serviceName).contains(operation)) {
            throw new AuthorizationException(String.format("Service %s not registered for operation %s",
                    serviceName, operation));
        }

    }

    private void validatePolicyId(List<AuthorizationPolicy> policies) throws AuthorizationException {
        if (!policies.stream().filter(p -> Utils.isEmpty(p.getPolicyId())).collect(Collectors.toList()).isEmpty()) {
            throw new AuthorizationException("Malformed policy with empty/null policy Id's");
        }
        // check for duplicates
        Set<String> duplicates = new HashSet<>();
        if (policies.stream().anyMatch(p -> !duplicates.add(p.getPolicyId()))) {
            throw new AuthorizationException("Malformed policy with duplicate policy Id's ");
        }
    }

    private void validateOperations(String serviceName, AuthorizationPolicy policy) throws AuthorizationException {
        Set<String> operations = policy.getOperations();
        if (Utils.isEmpty(operations)) {
            throw new AuthorizationException("Malformed policy with invalid/empty operations: "
                    + policy.getPolicyId());
        }
        Set<String> supportedOps = serviceToOperationsMap.get(serviceName);
        // check if operations are valid and registered.
        if (operations.stream().anyMatch(o -> !supportedOps.contains(o))) {
            throw new AuthorizationException(String.format("Operation not registered with service %s", serviceName));
        }
    }

    private void validatePrincipals(AuthorizationPolicy policy) throws AuthorizationException {
        Set<String> principals = policy.getPrincipals();
        if (Utils.isEmpty(principals)) {
            throw new AuthorizationException("Malformed policy with invalid/empty principal: " + policy.getPolicyId());
        }
        // check if principal is a valid EG service
        List<String> unknownSources = principals.stream().filter(s -> !s.equals(ANY_REGEX)).filter(s ->
                kernel.findServiceTopic(s) == null).collect(Collectors.toList());

        if (!unknownSources.isEmpty()) {
            throw new AuthorizationException(
                    String.format("Principal %s in auth policy are not valid services", unknownSources));
        }
    }

    private void addPermission(String destination,
                               Set<String> principals,
                               Set<String> operations,
                               Set<String> resources) throws AuthorizationException {
        // Method assumes that all inputs are valid now
        for (String principal: principals) {
            for (String operation: operations) {
                if (resources == null || resources.isEmpty()) {
                    authModule.addPermission(destination,
                            Permission.builder().principal(principal).operation(operation).resource(null).build());
                } else {
                    for (String resource : resources) {
                        authModule.addPermission(destination,
                                Permission.builder()
                                        .principal(principal)
                                        .operation(operation)
                                        .resource(resource)
                                        .build());
                    }
                }
            }
        }
    }
}
