package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.AuthorizationPolicy.PolicyComponentTypes;
import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aws.iot.evergreen.kernel.EvergreenService.ACCESS_CONTROL_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.util.Coerce.toEnum;

public final class AuthorizationPolicyParser {

    /**
     * Given a Topics ACL object, construct and return a List of AuthorizationPolicy objects that may exist.
     * Never returns null.
     *
     * @param componentName      String
     * @param accessControlTopic Topic
     * @param logger             Logger
     * @return {@List} of {@AuthorizationPolicy}'s
     * @throws AuthorizationException if there is a problem loading the policies.
     */
    public List<AuthorizationPolicy> parseAuthorizationPolicyList(String componentName, Topic accessControlTopic,
                                                                  Logger logger) {
        List<AuthorizationPolicy> newAuthorizationPolicyList = new ArrayList<>();

        Object accessControlTopicObject = accessControlTopic.getOnce();

        if (!(accessControlTopicObject instanceof List)
                || Utils.isEmpty((List) accessControlTopicObject)
                || !(((List) accessControlTopicObject).get(0) instanceof Map)) {
            logger.atWarn("load-authorization-access-control-list-retrieval-error")
                    .log("Access Control List is missing or invalid for component {}", componentName);
            return newAuthorizationPolicyList;
        }

        List<Map<String, Object>> accessControlList = (List<Map<String, Object>>) accessControlTopicObject;

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
                            logger.atError("load-authorization-config-unknown policy key")
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

    /**
     * Given a kernel object, construct and return a map of AuthorizationPolicy objects that may exist,
     * grouped into lists of the same policyType.
     * Never returns null.
     *
     * @param kernel Kernel
     * @param logger Logger
     * @return {@Map} of {@String} keys and {@List} of {@AuthorizationPolicy}'s as  values"
     * @throws AuthorizationException if there is a problem loading the policies.
     */
    public Map<String, List<AuthorizationPolicy>> parseAllAuthorizationPolicies(Kernel kernel, Logger logger) {

        Map<String, List<AuthorizationPolicy>> authorizationPolicyMap = new HashMap<>();

        Collection<EvergreenService> allServices = kernel.orderedDependencies();
        for (EvergreenService service : allServices) {
            Topics serviceConfig = service.getConfig();

            if (serviceConfig == null) {
                logger.atWarn("load-authorization-component-config-retrieval-error")
                        .log("No config found for service {}.", service.getName());
                continue;
            }

            String componentName = serviceConfig.getName();
            if (Utils.isEmpty(componentName)) {
                logger.atWarn("load-authorization-component-name-null")
                        .log("Service config name is missing");
                continue;
            }
            //Get the Access Control List for this component
            Topics accessControlTopics = serviceConfig.findTopics(ACCESS_CONTROL_NAMESPACE_TOPIC);
            if (accessControlTopics == null) {
                logger.atWarn("load-authorization-component-no-access-control-list-found")
                        .log("Component {} has no valid accessControl component field.", componentName);
                continue;
            }

            //Iterate through each ACL type
            for (Node accessControlListNode : accessControlTopics) {
                Topic accessControlTopic = (Topic) accessControlListNode;
                String policyType = accessControlTopic.getName();
                List<AuthorizationPolicy> newAuthorizationPolicyList = parseAuthorizationPolicyList(
                        componentName, accessControlTopic, logger);

                //If the policyType already exists in the map
                if (authorizationPolicyMap.containsKey(policyType)) {
                    authorizationPolicyMap.get(policyType).addAll(newAuthorizationPolicyList);
                } else {
                    authorizationPolicyMap.put(policyType, newAuthorizationPolicyList);
                }
            }
        }

        return authorizationPolicyMap;
    }
}
