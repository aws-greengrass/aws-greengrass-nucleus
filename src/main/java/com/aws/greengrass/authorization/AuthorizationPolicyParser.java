/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.authorization.AuthorizationPolicy.PolicyComponentTypes;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.ACCESS_CONTROL_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.util.Coerce.toEnum;

public final class AuthorizationPolicyParser {
    private static final Logger logger = LogManager.getLogger(AuthorizationPolicyParser.class);
    private static final ObjectMapper OBJECT_MAPPER =
            JsonMapper.builder().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true).build();

    /**
     * Given a kernel object, construct and return a map of AuthorizationPolicy objects that may exist,
     * grouped into lists of the same policy type.
     * This is used only upon kernel startup, to initialize all policies.
     * Never returns null.
     *
     * @param kernel Kernel
     * @return {@Map} of {@String} keys and {@List} of {@AuthorizationPolicy}'s as  values"
     */
    public Map<String, List<AuthorizationPolicy>> parseAllAuthorizationPolicies(Kernel kernel) {

        Map<String, List<AuthorizationPolicy>> masterAuthorizationPolicyMap = new HashMap<>();

        Topics allServices = kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC);

        if (allServices == null) {
            logger.atInfo("load-authorization-all-services-component-config-retrieval-error")
                    .log("Unable to retrieve services config");
            return masterAuthorizationPolicyMap;
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

            Topic accessControlMapTopic = serviceConfig.find(PARAMETERS_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
            if (accessControlMapTopic == null) {
                continue;
            }

            //Retrieve all policies, mapped to each policy type
            Map<String, List<AuthorizationPolicy>> componentAuthorizationPolicyMap = parseAllPoliciesForComponent(
                    accessControlMapTopic, componentName);

            if (componentAuthorizationPolicyMap != null) {
                //For each policy type (e.g. aws.greengrass.ipc.pubsub)
                for (Map.Entry<String, List<AuthorizationPolicy>> policyTypeList :
                        componentAuthorizationPolicyMap.entrySet()) {

                    String policyType = policyTypeList.getKey();
                    List<AuthorizationPolicy> policyList = policyTypeList.getValue();

                    //If multiple components have policies for the same policy type
                    masterAuthorizationPolicyMap.computeIfAbsent(policyType, k -> new ArrayList<>()).addAll(policyList);
                }
            }
        }
        return masterAuthorizationPolicyMap;
    }

    /**
     * Given a accessControlMapTopic Topic, construct and return a map of AuthorizationPolicy objects that may exist
     * only for that component config, grouped into lists of the same policy type.
     * Never returns null.
     *
     * @param accessControlMapTopic Topic
     * @param componentName String
     * @return {@Map} of {@String} keys and {@List} of {@AuthorizationPolicy}'s as  values"
     */
    private Map<String, List<AuthorizationPolicy>> parseAllPoliciesForComponent(Topic accessControlMapTopic,
                                                                                String componentName) {
        Map<String, List<AuthorizationPolicy>> authorizationPolicyMap = new HashMap<>();

        String accessControlMapValueJson = Coerce.toString(accessControlMapTopic);
        if (Utils.isEmpty(accessControlMapValueJson)) {
            return authorizationPolicyMap;
        }

        Map<String, Object> accessControlMap;
        try {
            accessControlMap = OBJECT_MAPPER.readValue(accessControlMapValueJson,
                    new TypeReference<Map<String, Object>>(){});
        } catch (JsonProcessingException e) {
            logger.atError("load-authorization-config-deserialization-error", e)
                    .log("Unable to deserialize access control map");
            return authorizationPolicyMap;
        }

        if (accessControlMap == null) {
            return authorizationPolicyMap;
        }

        //For each policy type
        for (Map.Entry<String, Object> accessControlType : accessControlMap.entrySet()) {

            String policyType = accessControlType.getKey();
            Object accessControlTopicObject = accessControlType.getValue();

            if (!(accessControlTopicObject instanceof List)
                    || Utils.isEmpty((List) accessControlTopicObject)
                    || !(((List) accessControlTopicObject).get(0) instanceof Map)) {
                logger.atInfo("load-authorization-access-control-list-retrieval")
                        .log("Access Control List is missing or invalid for type {} of component {}",
                                policyType, componentName);
                continue;
            }

            List<Map<String, Object>> accessControlList = (List<Map<String, Object>>) accessControlTopicObject;

            List<AuthorizationPolicy> newAuthorizationPolicyList = parseAuthorizationPolicyList(
                    componentName, accessControlList);

            authorizationPolicyMap.put(policyType, newAuthorizationPolicyList);
        }
        return authorizationPolicyMap;
    }

    /**
     * Given a Topics ACL object, construct and return a List of AuthorizationPolicy objects that may exist.
     * Never returns null.
     *
     * @param componentName      String
     * @param accessControlList  List of Map of String keys and Object values
     * @return {@List} of {@AuthorizationPolicy}'s
     */
    private List<AuthorizationPolicy> parseAuthorizationPolicyList(String componentName,
                                                                   List<Map<String, Object>> accessControlList) {
        List<AuthorizationPolicy> newAuthorizationPolicyList = new ArrayList<>();

        //Iterate through each policy
        for (Map<String, Object> allPoliciesMap : accessControlList) {
            for (Map.Entry policyEntry : allPoliciesMap.entrySet()) {

                //Initialize these components to null
                String policyDescription = null;
                Set<String> operations = null;
                Set<String> resources = null;

                Map<String, Object> policyMap;

                //Retrieve the actual policy specifications
                if (!(policyEntry.getValue() instanceof Map)) {
                    logger.atWarn("load-authorization-access-control-list-policy-retrieval-error")
                            .log("Error while retrieving an Access Control List policy");
                    continue;
                }
                policyMap = (Map<String, Object>) policyEntry.getValue();
                String policyId = Coerce.toString(policyEntry.getKey());

                for (Map.Entry policyComponent : policyMap.entrySet()) {
                    //Iterate through the components of this policy
                    PolicyComponentTypes policyComponentKey = toEnum(
                            PolicyComponentTypes.class,
                            Coerce.toString(policyComponent.getKey()),
                            PolicyComponentTypes.UNKNOWN);
                    switch (policyComponentKey) {
                        case POLICY_DESCRIPTION:
                            policyDescription = Coerce.toString(policyComponent.getValue());
                            break;
                        case OPERATIONS:
                            operations = new HashSet<>(Coerce.toStringList(policyComponent.getValue()));
                            break;
                        case RESOURCES:
                            resources = new HashSet<>(Coerce.toStringList(policyComponent.getValue()));
                            break;
                        default:
                            logger.atError("load-authorization-config-unknown-policy-key")
                                    .log("Component {} has an invalid policy key {} in policy {}",
                                            componentName,
                                            policyComponent.getKey(),
                                            policyId);
                            continue;
                    }
                }

                if (Utils.isEmpty(operations)) {
                    String errorMessage = "Policy operations are missing or invalid";
                    logger.atError("load-authorization-missing-policy-component-operations")
                            .log(errorMessage);
                    continue;
                }

                AuthorizationPolicy newPolicy = AuthorizationPolicy.builder()
                        .policyId(policyId)
                        .policyDescription(policyDescription)
                        .principals(java.util.Collections.singleton(componentName))
                        .operations(operations)
                        .resources(resources)
                        .build();

                newAuthorizationPolicyList.add(newPolicy);
            }
        }

        return newAuthorizationPolicyList;
    }
}
