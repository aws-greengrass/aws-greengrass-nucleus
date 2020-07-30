package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.AuthorizationPolicy.PolicyComponentTypes;
import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.util.Coerce;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AuthorizationUtils {

    private static final String ACCESS_CONTROL_KEY = "AccessControl";

    private AuthorizationUtils(){
    }


    /**
     * Given a Topics config object, construct and return a list of AuthorizationPolicy objects that may exist.
     * @param config Topics
     * @return List AuthorizationPolicy
     * @throws AuthorizationException if there is a problem loading the policies.
     */
    public static List<AuthorizationPolicy> parseAuthorizationPolicy(Topics config) throws AuthorizationException {

        Topics accessControlMap = config.findTopics(ACCESS_CONTROL_KEY);
        if (accessControlMap == null) {
            return null;
        }

        ArrayList<AuthorizationPolicy> authorizationPolicyList = new ArrayList<>();

        //Iterate through all policies
        for (Node policyObject : accessControlMap) {

            //Initialize these components to null
            String policyDescription = null;
            Set<String> principals = null;
            Set<String> operations = null;
            Set<String> resources = null;

            for (Node policyComponentNode : (Topics) policyObject) {
                //Iterate through the components of this policy
                Topic policyComponent = (Topic) policyComponentNode;
                String policyComponentType = policyComponent.getName().toUpperCase();
                PolicyComponentTypes policyComponentKey = PolicyComponentTypes.valueOf(policyComponentType);
                switch (policyComponentKey) {
                    case POLICYDESCRIPTION:
                        policyDescription = Coerce.toString(policyComponent);
                        break;
                    case PRINCIPALS:
                        principals = new HashSet<>(Coerce.toStringList(policyComponent));
                        break;
                    case OPERATIONS:
                        operations = new HashSet<>(Coerce.toStringList(policyComponent));
                        break;
                    case RESOURCES:
                        resources = new HashSet<>(Coerce.toStringList(policyComponent));
                        break;
                    default:
                        throw new AuthorizationException("Unknown policy component key.");
                    }
            }

            authorizationPolicyList.add(AuthorizationPolicy.builder()
                    .policyId(policyObject.getName())
                    .policyDescription(policyDescription)
                    .principals(principals)
                    .operations(operations)
                    .resources(resources)
                    .build());
        }

        return authorizationPolicyList;
    }
}
