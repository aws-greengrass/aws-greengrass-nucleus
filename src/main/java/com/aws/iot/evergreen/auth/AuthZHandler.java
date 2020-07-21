/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthZException;
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
public class AuthZHandler {
    private static final String ANY_REGEX = "*";
    private static final Logger logger = LogManager.getLogger(AuthZHandler.class);
    private final AuthZModule authModule;
    private final ConcurrentHashMap<String, Set<String>> serviceToOperationsMap;
    private final ConcurrentHashMap<String, List<AuthZPolicy>> serviceToAuthZConfig;
    private final Kernel kernel;

    /**
     * Constructor for AuthZ.
     * @param kernel kernel module for getting service information
     */
    @Inject
    public AuthZHandler(Kernel kernel) {
        authModule = new AuthZModule();
        serviceToOperationsMap = new ConcurrentHashMap<>();
        serviceToAuthZConfig = new ConcurrentHashMap<>();
        this.kernel = kernel;
    }

    /**
     * Check if the combination of source, destination, operation and resource is an allowed flow.
     * Flow can be thought of as a request which originates from {@code source} service destined for
     * {@code destination} service, which needs access to {@code resource} using API {@code operation}.
     * @param destination Destination service which is being accessed.
     * @param permission container for source, operation and resource
     * @return whether the input combination is a valid flow.
     * @throws AuthZException when flow is not authorized.
     */
    public boolean isFlowAuthorized(String destination, Permission permission) throws AuthZException {
        String source = permission.getSource();
        String operation = permission.getOperation();
        String resource = permission.getResource();
        // If the operation is not registered with the destination service, then fail
        isOperationValid(destination, operation);

        // Lookup all possible allow configurations starting from most specific to least
        // This helps for access logs, as customer can figure out which policy is being hit.
        String[][] combinations = {
                {destination, source, operation, resource},
                {destination, source, operation, ANY_REGEX},
                {destination, source, ANY_REGEX, resource},
                {destination, ANY_REGEX, operation, resource},
                {destination, source, ANY_REGEX, ANY_REGEX},
                {destination, ANY_REGEX, operation, ANY_REGEX},
                {destination, ANY_REGEX, ANY_REGEX, resource},
                {destination, ANY_REGEX, ANY_REGEX, ANY_REGEX},
        };

        for (String[] combination: combinations) {
            if (authModule.isPresent(combination[0],
                    Permission.builder()
                            .source(combination[1])
                            .operation(combination[2])
                            .resource(combination[3])
                            .build())) {
                logger.atDebug().log("Hit policy with source {}, operation {}, resource {}",
                        combination[1],
                        combination[2],
                        combination[3]);
                return true;
            }
        }
        throw new AuthZException(
                String.format("Source %s is not authorized to perform %s:%s on resource %s",
                        source,
                        destination,
                        operation,
                        resource));
    }

    /**
     * Register a service with AuthZ module. This should only be called once in a lifetime of kernel
     * and operations are strings which the service intends to match for incoming requests by calling
     * {@link #isFlowAuthorized(Permission) isFlowAuthorized} method
     * @param serviceName Name of the service to be registered.
     * @param operations Set of operations the service needs to register with AuthZ.
     * @throws AuthZException If service is already registered.
     */
    public void registerService(String serviceName, Set<String> operations)
            throws AuthZException {
        if (Utils.isEmpty(operations)) {
            throw new AuthZException("operations is empty");
        }
        if (serviceToOperationsMap.containsKey(serviceName)) {
            throw new AuthZException("Service already registered: " + serviceName);
        }

        operations.add(ANY_REGEX);
        Set<String> operationsCopy = Collections.unmodifiableSet(new HashSet<>(operations));
        serviceToOperationsMap.putIfAbsent(serviceName, operationsCopy);
    }

    /**
     * Loads authZ config for future auth lookups. The config should not have confidential
     * values. This method assumes that the service names for source and destination,
     * the operations and resources must not be secret and can be logged or shared if required.
     * @param serviceName Destination service which intents to supply auth config
     * @param config config which has list of policies. All policies are treated as separate
     *               and no merging or joins happen. Duplicated config would result in duplicated
     *               permissions but would not impact functionality.
     * @throws AuthZException if there is a problem loading the config.
     */
    public void loadAuthZConfig(String serviceName, List<AuthZPolicy> config)
            throws AuthZException {
        // TODO: Make this method atomic operation or thread safe for manipulating
        // underlying permission store.
        if (Utils.isEmpty(serviceName)) {
            throw new AuthZException("Service name is not specified");
        }
        if (config == null) {
            throw new AuthZException("config is null");
        }
        isServiceRegistered(serviceName);

        // First validate if all sources and operations are valid
        for (AuthZPolicy policy: config) {
            validateSources(policy);
            validateOperations(serviceName, policy);
        }
        // now start adding the config as permissions
        for (AuthZPolicy policy: config) {
            addPermission(serviceName, policy.getSources(), policy.getOperations(), policy.getResources());
        }
        this.serviceToAuthZConfig.put(serviceName, config);
    }

    private void isServiceRegistered(String serviceName) throws AuthZException {
        if (Utils.isEmpty(serviceName)) {
            throw new AuthZException("Invalid service name: " + serviceName);
        }
        if (!serviceToOperationsMap.containsKey(serviceName)) {
            throw new AuthZException("Service not registered: " + serviceName);
        }
    }

    private void isOperationValid(String serviceName, String operation)
            throws AuthZException {
        isServiceRegistered(serviceName);
        if (!serviceToOperationsMap.get(serviceName).contains(operation)) {
            throw new AuthZException(String.format("Service %s not registered for operation %s",
                    serviceName, operation));
        }

    }

    private void validateOperations(String serviceName, AuthZPolicy policy) throws AuthZException {
        Set<String> operations = policy.getOperations();
        if (Utils.isEmpty(operations)) {
            throw new AuthZException("Malformed policy with invalid/empty operations: "
                    + policy.getPolicyId());
        }
        Set<String> supportedOps = serviceToOperationsMap.get(serviceName);
        // check if operations are valid and registered.
        if (operations.stream().anyMatch(o -> !supportedOps.contains(o))) {
            throw new AuthZException(String.format("Operation not registered with service %s", serviceName));
        }
    }

    private void validateSources(AuthZPolicy policy) throws AuthZException {
        Set<String> sources = policy.getSources();
        if (Utils.isEmpty(sources)) {
            throw new AuthZException("Malformed policy with invalid/empty source: " + policy.getPolicyId());
        }
        // check if source is a valid EG service
        List<String> unknownSources = sources.stream().filter(s -> !s.equals(ANY_REGEX)).filter(s ->
                kernel.findServiceTopic(s) == null).collect(Collectors.toList());

        if (!unknownSources.isEmpty()) {
            throw new AuthZException(String.format("Source %s in auth policy are not valid services", unknownSources));
        }
    }

    private void addPermission(String destination,
                               Set<String> sources,
                               Set<String> operations,
                               Set<String> resources) throws AuthZException {
        // Method assumes that all inputs are valid now
        for (String source: sources) {
            for (String operation: operations) {
                if (resources == null || resources.isEmpty()) {
                    authModule.addPermission(destination,
                            Permission.builder().source(source).operation(operation).resource(null).build());
                } else {
                    for (String resource : resources) {
                        authModule.addPermission(destination,
                                Permission.builder().source(source).operation(operation).resource(resource).build());
                    }
                }
            }
        }
    }
}
