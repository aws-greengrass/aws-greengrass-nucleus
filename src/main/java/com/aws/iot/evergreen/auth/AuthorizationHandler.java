/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

import static com.aws.iot.evergreen.kernel.EvergreenService.ACCESS_CONTROL_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Kernel.findServiceForNode;
import static com.aws.iot.evergreen.tes.TokenExchangeService.AUTHZ_TES_OPERATION;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;

/**
 * Main module which is responsible for handling AuthZ for evergreen. This only manages
 * the AuthZ configuration and performs lookups based on the config. Config is just a copy of
 * customer config and this module does not try to optimize storage. For instance,
 * if customer specifies same policies twice, we treat and store them separately. Components are
 * identified by their service identifiers (component names) and operation/resources are assumed to be
 * opaque strings. They are not treated as confidential and it should be the responsibility
 * of the caller to use proxy identifiers for confidential data. Implementation optimizes for fast lookups
 * and not for storage.
 */
@Singleton
public class AuthorizationHandler  {
    private static final String ANY_REGEX = "*";
    private static final Logger logger = LogManager.getLogger(AuthorizationHandler.class);
    private final ConcurrentHashMap<String, Set<String>> componentToOperationsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<AuthorizationPolicy>>
            componentToAuthZConfig = new ConcurrentHashMap<>();
    private final Kernel kernel;

    private final AuthorizationModule authModule;
    
    /**
     * Constructor for AuthZ.
     *
     * @param kernel kernel module for getting component information
     * @param authModule authorization module to store the authorization state
     * @param policyParser for parsing a given policy ACL
     */
    @Inject
    public AuthorizationHandler(Kernel kernel,  AuthorizationModule authModule,
                                AuthorizationPolicyParser policyParser) {
        this.kernel = kernel;
        this.authModule = authModule;

        Map<String, List<AuthorizationPolicy>> componentNameToPolicies = policyParser.parseAllAuthorizationPolicies(
                kernel, logger);
        for (Map.Entry<String, List<AuthorizationPolicy>> acl : componentNameToPolicies.entrySet()) {
            this.loadAuthorizationPolicies(acl.getKey(), acl.getValue(), false);
        }

        //Load default policy for TES
        this.loadAuthorizationPolicies(TOKEN_EXCHANGE_SERVICE_TOPICS,
                getDefaultPolicyForService(AUTHZ_TES_OPERATION),
                false);

        //Subscribe to future auth config updates
        this.kernel.getConfig().getRoot().subscribe(
                (why, newv) -> {
                    if (newv == null) {
                        return;
                    }
                    if (!newv.childOf(ACCESS_CONTROL_NAMESPACE_TOPIC)) {
                        return;
                    }
                    if (!(newv instanceof Topic)) {
                        logger.atError("update-authorization-formatting-error")
                                .addKeyValue("InvalidNodeName", newv.getFullName())
                                .log("Incorrect formatting while updating the authorization ACL.");
                        return;
                    }
                    Topic updatedTopic = (Topic) newv;
                    String componentName = findServiceForNode(updatedTopic);
                    //If there is a change in a node, reload that one component's ACL
                    List<AuthorizationPolicy> updatedComponentPolicies = policyParser
                            .parseAuthorizationPolicyList(componentName, updatedTopic, logger);
                    this.loadAuthorizationPolicies(updatedTopic.getName(), updatedComponentPolicies, true);

                });
    }

    /**
     * Check if the combination of destination, principal, operation and resource is allowed.
     * A scenario where this method is called is for a request which originates from {@code principal}
     * component destined for {@code destination} component, which needs access to {@code resource}
     * using API {@code operation}.
     *
     * @param destination Destination component which is being accessed.
     * @param permission  container for principal, operation and resource
     * @return whether the input combination is a valid flow.
     * @throws AuthorizationException when flow is not authorized.
     */
    public boolean isAuthorized(String destination, Permission permission) throws AuthorizationException {
        String principal = permission.getPrincipal();
        String operation = permission.getOperation();
        String resource = permission.getResource();
        // If the operation is not registered with the destination component, then fail
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

        for (String[] combination : combinations) {
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
     * Register a component with AuthZ module. This registers an evergreen component with authorization module.
     * This is required to register list of operations supported by a component especially for 3P component
     * in future, whose operations might not be known at bootstrap.
     * Operations are identifiers which the components intend to match for incoming requests by calling
     * {@link #isAuthorized(String, Permission)} isAuthorized} method.
     *
     * @param componentName Name of the component to be registered.
     * @param operations    Set of operations the component needs to register with AuthZ.
     * @throws AuthorizationException If component is already registered.
     */
    public void registerComponent(String componentName, Set<String> operations)
            throws AuthorizationException {
        if (Utils.isEmpty(operations) || Utils.isEmpty(componentName)) {
            throw new AuthorizationException("Invalid arguments for registerComponent()");
        }
        operations.add(ANY_REGEX);
        componentToOperationsMap.computeIfAbsent(componentName, k -> new HashSet<>()).addAll(operations);

    }

    /**
     * Loads authZ policies for a single component for future auth lookups. The policies should not have confidential
     * values. This method assumes that the component names for principal and destination,
     * the operations and resources must not be secret and can be logged or shared if required.
     * If the isUpdate flag is specified, this method will clear the existing policies for a component before
     * refreshing with the updated list.
     *
     * @param componentName Destination component which intents to supply auth policies
     * @param policies      policies which has list of policies. All policies are treated as separate
     *                      and no merging or joins happen. Duplicated policies would result in duplicated
     *                      permissions but would not impact functionality.
     * @param isUpdate      If this load request is to update existing policies for a component.
     */
    public void loadAuthorizationPolicies(String componentName, List<AuthorizationPolicy> policies, boolean isUpdate) {
        if (Utils.isEmpty(policies)) {
            return;
        }

        try {
            isComponentRegistered(componentName);
        } catch (AuthorizationException e) {
            logger.atError("load-authorization-config-invalid-component", e)
                    .log("Component {} is invalid or not registered with the AuthorizationHandler",
                            componentName);
            return;
        }

        try {
            validatePolicyId(policies);
        } catch (AuthorizationException e) {
            logger.atError("load-authorization-config-invalid-policy", e)
                    .log("Component {} contains an invalid policy", componentName);
            return;
        }

        // First validate if all principals and operations are valid
        for (AuthorizationPolicy policy : policies) {
            try {
                validatePrincipals(policy);
            } catch (AuthorizationException e) {
                logger.atError("load-authorization-config-invalid-principal", e)
                        .log("Component {} contains an invalid principal in policy {}", componentName,
                                policy.getPolicyId());
                continue;
            }
            try {
                validateOperations(componentName, policy);
            } catch (AuthorizationException e) {
                logger.atError("load-authorization-config-invalid-operation", e)
                        .log("Component {} contains an invalid operation in policy {}", componentName,
                                policy.getPolicyId());
                continue;
            }
        }
        if (isUpdate) {
            authModule.clearComponentPermissions(componentName);
        }
        // now start adding the policies as permissions
        for (AuthorizationPolicy policy : policies) {
            try {
                addPermission(componentName, policy.getPrincipals(), policy.getOperations(), policy.getResources());
            } catch (AuthorizationException e) {
                logger.atError("load-authorization-config-add-permission-error", e)
                        .log("Error while loading policy {} for component {}", policy.getPolicyId(),
                                componentName);
                continue;
            }
        }

        this.componentToAuthZConfig.put(componentName, policies);

    }

    private void isComponentRegistered(String componentName) throws AuthorizationException {
        if (Utils.isEmpty(componentName)) {
            throw new AuthorizationException("Component name is not specified: " + componentName);
        }
        //TODO: solve the issue where the authhandler starts up and loads policies before services are registered:
        // https://issues-iad.amazon.com/issues/V234938383
        //if (!componentToOperationsMap.containsKey(componentName)) {
        //throw new AuthorizationException("Component not registered: " + componentName);
        //}
    }

    private void isOperationValid(String componentName, String operation)
            throws AuthorizationException {
        isComponentRegistered(componentName);
        if (!componentToOperationsMap.get(componentName).contains(operation)) {
            throw new AuthorizationException(String.format("Component %s not registered for operation %s",
                    componentName, operation));
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

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void validateOperations(String componentName, AuthorizationPolicy policy) throws AuthorizationException {
        Set<String> operations = policy.getOperations();
        if (Utils.isEmpty(operations)) {
            throw new AuthorizationException("Malformed policy with invalid/empty operations: "
                    + policy.getPolicyId());
        }
        //TODO: solve the issue where the authhandler starts up and loads policies before services are registered:
        // https://issues-iad.amazon.com/issues/V234938383
        //Set<String> supportedOps = componentToOperationsMap.get(componentName);
        // check if operations are valid and registered.
        //if (operations.stream().anyMatch(o -> !supportedOps.contains(o))) {
        //throw new AuthorizationException(
        //String.format("Operation not registered with component %s", componentName));
        //}
    }

    private void validatePrincipals(AuthorizationPolicy policy) throws AuthorizationException {
        Set<String> principals = policy.getPrincipals();
        if (Utils.isEmpty(principals)) {
            throw new AuthorizationException("Malformed policy with invalid/empty principal: " + policy.getPolicyId());
        }
        // check if principal is a valid EG component
        List<String> unknownSources = principals.stream().filter(s -> !s.equals(ANY_REGEX)).filter(s ->
                kernel.findServiceTopic(s) == null).collect(Collectors.toList());

        if (!unknownSources.isEmpty()) {
            throw new AuthorizationException(
                    String.format("Principal %s in auth policy are not valid components", unknownSources));
        }
    }

    private void addPermission(String destination,
                               Set<String> principals,
                               Set<String> operations,
                               Set<String> resources) throws AuthorizationException {
        // Method assumes that all inputs are valid now
        for (String principal : principals) {
            for (String operation : operations) {
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

    private List<AuthorizationPolicy> getDefaultPolicyForService(String serviceName) {
        String defaultPolicyDesc = String.format("Default policy for {}", serviceName);
        return Arrays.asList(AuthorizationPolicy.builder()
                .policyId(UUID.randomUUID().toString())
                .policyDescription(defaultPolicyDesc)
                .principals(new HashSet<>(Arrays.asList("*")))
                .operations(new HashSet<>(Arrays.asList(serviceName)))
                .build());
    }
}
