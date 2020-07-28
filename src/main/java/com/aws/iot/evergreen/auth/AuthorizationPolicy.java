/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.util.Coerce;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Value
@Builder
public class AuthorizationPolicy {
    @NonNull String policyId;
    String policyDescription;
    @NonNull Set<String> principals;
    @NonNull Set<String> operations;
    Set<String> resources;

    enum PolicyComponentTypes {
        POLICYDESCRIPTION("policyDescription"),
        PRINCIPALS("principals"),
        OPERATIONS("operations"),
        RESOURCES("resources");

        private final String name;

        PolicyComponentTypes(String s) {
            name = s;
        }

        public boolean equalsName(String otherName) {
            return name.equals(otherName);
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    /**
     * Given a Topics config object, construct and return a list of AuthorizationPolicy objects that may exist.
     * @param config Topics
     * @return List AuthorizationPolicy
     * @throws AuthorizationException if there is a problem loading the policies.
     */
    public static List<AuthorizationPolicy> parseAuthorizationPolicy(Topics config) throws AuthorizationException {
        if (config.isEmpty()) {
            throw new AuthorizationException(String.format("Empty config found when parsing authorization policy"));
        }

        Node accessControlMap = config.children.get("AccessControl");
        if (accessControlMap == null) {
            return null;
        }

        ArrayList<AuthorizationPolicy> authorizationPolicyList = new ArrayList<>();

        //Retrieve the Map containing all policies
        Map policies = (Map) accessControlMap.toPOJO();

        //Iterate through all policies
        for (Object policyObject : policies.entrySet()) {
            Map.Entry policyMapEntry = (Map.Entry) policyObject;

            //Initialize these components to null
            String policyDescription = null;
            Set<String> principals = null;
            Set<String> operations = null;
            Set<String> resources = null;

            //Retrieve the policyId for this policy
            String policyId = Coerce.toString(policyMapEntry.getKey());

            //Retrieve the actual policy specifications
            Map policyMap = (Map) policyMapEntry.getValue();

            //Iterate through the components of this policy
            for (Object policyComponentObject : policyMap.entrySet()) {
                Map.Entry policyComponent = (Map.Entry) policyComponentObject;
                String policyComponentKey = Coerce.toString(policyComponent.getKey());
                if (policyComponentKey.equals(PolicyComponentTypes.POLICYDESCRIPTION.toString())) {
                    policyDescription = Coerce.toString(policyComponent.getValue());
                } else if (policyComponentKey.equals(PolicyComponentTypes.PRINCIPALS.toString())) {
                    principals = new HashSet<>(Coerce.toStringList(policyComponent.getValue()));
                } else if (policyComponentKey.equals(PolicyComponentTypes.OPERATIONS.toString())) {
                    operations = new HashSet<>(Coerce.toStringList(policyComponent.getValue()));
                } else if (policyComponentKey.equals(PolicyComponentTypes.RESOURCES.toString())) {
                    resources = new HashSet<>(Coerce.toStringList(policyComponent.getValue()));
                }
            }

            authorizationPolicyList.add(AuthorizationPolicy.builder()
                    .policyId(policyId)
                    .policyDescription(policyDescription)
                    .principals(principals)
                    .operations(operations)
                    .resources(resources)
                    .build());
        }

        if (!authorizationPolicyList.isEmpty()) {
            return authorizationPolicyList;
        }

        return null;
    }
}
