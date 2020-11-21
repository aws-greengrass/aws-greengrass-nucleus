/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.ACCESS_CONTROL_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

public final class AuthorizationPolicyParser {
    private static final Logger logger = LogManager.getLogger(AuthorizationPolicyParser.class);
    private static final ObjectMapper OBJECT_MAPPER =
            JsonMapper.builder().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true).build();
    /**
     * Given a kernel object, construct and return a map of AuthorizationPolicy objects that may exist,
     * grouped into lists of the same destination component.
     * This is used only upon kernel startup, to initialize all policies.
     * Never returns null.
     *
     * @param kernel Kernel
     * @return {@Map} of {@String} keys and {@List} of {@AuthorizationPolicy}'s as values"
     */

    public Map<String, List<AuthorizationPolicy>> parseAllAuthorizationPolicies(Kernel kernel) {
        Map<String, List<AuthorizationPolicy>> primaryAuthorizationPolicyMap = new HashMap<>();
        Topics allServices = kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC);

        if (allServices == null) {
            logger.atInfo("load-authorization-all-services-component-config-retrieval-error")
                    .log("Unable to retrieve services config");
            return primaryAuthorizationPolicyMap;
        }

        //For each component
        for (Node service : allServices) {

            if (service == null) {
                continue;
            }

            if (!(service instanceof Topics)) {
                continue;
            }

            Topics serviceConfig = (Topics) service;
            String componentName = Kernel.findServiceForNode(serviceConfig);

            Node accessControlMapTopic = serviceConfig
                    .findNode(CONFIGURATION_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
            if (accessControlMapTopic == null) {
                continue;
            }

            // Retrieve all policies, mapped to each policy type
            Map<String, List<AuthorizationPolicy>> componentAuthorizationPolicyMap = parseAllPoliciesForComponent(
                    accessControlMapTopic, componentName);

            // For each policy type (e.g. aws.greengrass.ipc.pubsub)
            for (Map.Entry<String, List<AuthorizationPolicy>> policyTypeList :
                    componentAuthorizationPolicyMap.entrySet()) {

                String policyType = policyTypeList.getKey();
                List<AuthorizationPolicy> policyList = policyTypeList.getValue();

                //If multiple components have policies for the same policy type
                primaryAuthorizationPolicyMap.computeIfAbsent(policyType, k -> new ArrayList<>()).addAll(policyList);
            }
        }
        return primaryAuthorizationPolicyMap;
    }

    /**
     * Given a accessControlMapTopic Topic, construct and return a map of AuthorizationPolicy objects that may exist
     * only for that component config, grouped into lists of the same destination component.
     * Never returns null.
     *
     * @param accessControlTopic Topic access control configuration
     * @param sourceComponent String the source component which has the access control config
     * @return {@Map} of {@String} keys and {@List} of {@AuthorizationPolicy}'s as  values"
     */
    private Map<String, List<AuthorizationPolicy>> parseAllPoliciesForComponent(Node accessControlTopic,
                                                                                String sourceComponent) {
        Map<String, List<AuthorizationPolicy>> authorizationPolicyMap = new HashMap<>();
        Map<String, Map<String, AuthorizationPolicyConfig>> accessControlMap = new HashMap<>();
        /*
            Parse the config which is in the following format where first level key denotes the destination
            component principal and second level denotes the policy object keyed by a unique ID
              accessControl:
                aws.greengrass.ipc.pubsub:
                  policyId1:
                    policyDescription: access to pubsub topics 1
                    operations:
                      - publish
                      - subscribe
                    resources:
                      - /topic/1/#
                      - /longer/topic/example/
                  policyId2:
                    policyDescription: access to pubsub topics 2
                    operations:
                      - publish
                    resources:
                      - /publishOnlyTopic
                aws.greengrass.secretsManager:
                  policyId3:
                    policyDescription: access to secrets
                    operations:
                      - getsecret
                    resources:
                      - secret1
        */
        try {
            if (accessControlTopic instanceof Topics) {
                accessControlMap = OBJECT_MAPPER.convertValue(((Topics)accessControlTopic).toPOJO(),
                        new TypeReference<Map<String, Map<String, AuthorizationPolicyConfig>>>() {
                        });
            } else if (accessControlTopic instanceof Topic) {
                // If its a leaf node, try parsing it. Be flexible and do best to understand customer intent
                accessControlMap = OBJECT_MAPPER.readValue(Coerce.toString(accessControlTopic),
                        new TypeReference<Map<String, Map<String, AuthorizationPolicyConfig>>>() {
                        });
            } else {
                logger.atError("load-authorization-config-deserialization-error")
                        .log("Unable to deserialize access control map {} for {}", accessControlTopic.toString(),
                                sourceComponent);
            }
        } catch (IllegalArgumentException | IOException e) {
            logger.atError("load-authorization-config-deserialization-error").setCause(e)
                    .log("Unable to deserialize access control map {} for {}", accessControlTopic.toString(),
                            sourceComponent);
            return authorizationPolicyMap;
        }

        // For each destination principal parse the config
        for (Map.Entry<String, Map<String, AuthorizationPolicyConfig>> accessControl : accessControlMap.entrySet()) {

            String destinationComponent = accessControl.getKey();
            Map<String, AuthorizationPolicyConfig> accessControlValue = accessControl.getValue();

            List<AuthorizationPolicy> newAuthorizationPolicyList = parseAuthorizationPolicyConfig(
                    sourceComponent, accessControlValue);

            authorizationPolicyMap.put(destinationComponent, newAuthorizationPolicyList);
        }
        return authorizationPolicyMap;
    }

    /**
     * Given a destination specific ACL object, construct and return a List of AuthorizationPolicy objects
     * that may exist. Never returns null.
     *
     * @param componentName      String name of the component which has the configuration
     * @param accessControlConfig  access control config for a specific destination
     * @return {@List} of {@AuthorizationPolicy}'s
     */
    private List<AuthorizationPolicy>
    parseAuthorizationPolicyConfig(String componentName, Map<String, AuthorizationPolicyConfig> accessControlConfig) {
        List<AuthorizationPolicy> newAuthorizationPolicyList = new ArrayList<>();

        // Iterate through each policy
        for (Map.Entry<String, AuthorizationPolicyConfig> policyEntry : accessControlConfig.entrySet()) {
            AuthorizationPolicyConfig policyConfig = policyEntry.getValue();
            if (Utils.isEmpty(policyConfig.getOperations())) {
                String errorMessage = "Policy operations are missing or invalid";
                logger.atError("load-authorization-missing-policy-component-operations")
                        .log(errorMessage);
                continue;
            }

            AuthorizationPolicy newPolicy = AuthorizationPolicy.builder()
                    .policyId(policyEntry.getKey())
                    .policyDescription(policyConfig.getPolicyDescription())
                    .principals(java.util.Collections.singleton(componentName))
                    .operations(policyConfig.getOperations())
                    .resources(policyConfig.getResources())
                    .build();

            newAuthorizationPolicyList.add(newPolicy);
        }

        return newAuthorizationPolicyList;
    }
}
