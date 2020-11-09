/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.ipc.modules.CLIService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import javax.inject.Inject;

@AllArgsConstructor
@NoArgsConstructor
public class AuthenticationHandler implements InjectionActions {
    public static final String AUTHENTICATION_TOKEN_LOOKUP_KEY = "_AUTHENTICATION_TOKENS";
    public static final String SERVICE_UNIQUE_ID_KEY = "_UID";
    private static final Logger logger = LogManager.getLogger(AuthenticationHandler.class);

    @Inject
    private Configuration config;

    /**
     * Register an authentication token for the given service.
     *
     * @param s service to generate an authentication token for
     */
    public static void registerAuthenticationToken(GreengrassService s) {
        Topic uid = s.getPrivateConfig().createLeafChild(SERVICE_UNIQUE_ID_KEY).withParentNeedsToKnow(false);
        String authenticationToken = Utils.generateRandomString(16).toUpperCase();
        uid.withValue(authenticationToken);
        Topics tokenTopics = s.getServiceConfig().parent.lookupTopics(AUTHENTICATION_TOKEN_LOOKUP_KEY);
        tokenTopics.withParentNeedsToKnow(false);

        Topic tokenTopic = tokenTopics.createLeafChild(authenticationToken);

        // If the authentication token was already registered, that's an issue, so we will retry
        // generating a new token in that case
        if (tokenTopic.getOnce() == null) {
            tokenTopic.withValue(s.getName());
        } else {
            registerAuthenticationToken(s);
        }
    }

    /**
     * Register an auth token for an external client which is not part of Greengrass. Only authenticated EG service can
     * register such a token.
     * @param requestingAuthToken Auth token of the requesting service
     * @param clientIdentifier The identifier to identify the client for which the token is being requested
     * @return Auth token.
     * @throws UnauthenticatedException thrown when the requestAuthToken is invalid
     */
    public String registerAuthenticationTokenForExternalClient(String requestingAuthToken,
                                                               String clientIdentifier)
            throws UnauthenticatedException {
        authenticateRequestsForExternalClient(requestingAuthToken);
        return generateAuthenticationToken(clientIdentifier);
    }

    private String generateAuthenticationToken(String clientIdentifier) {
        String authenticationToken = Utils.generateRandomString(16).toUpperCase();
        Topics tokenTopics = config.lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC,
                AUTHENTICATION_TOKEN_LOOKUP_KEY);
        tokenTopics.withParentNeedsToKnow(false);

        Topic tokenTopic = tokenTopics.createLeafChild(authenticationToken);

        // If the authentication token was already registered, that's an issue, so we will retry
        // generating a new token in that case
        if (tokenTopic.getOnce() == null) {
            tokenTopic.withValue(clientIdentifier);
            return authenticationToken;
        } else {
            return generateAuthenticationToken(clientIdentifier);
        }
    }

    private void authenticateRequestsForExternalClient(String requestingAuthToken) throws UnauthenticatedException {
        String authenticatedService = doAuthentication(requestingAuthToken);
        // Making it available only for CLIService right now. If it needs to be extended, requesting service can be
        // taken as a parameter
        if (!authenticatedService.equals(CLIService.CLI_SERVICE)) {
            logger.atError().kv("requestingServiceName", CLIService.CLI_SERVICE)
                    .log("Invalid requesting auth token for service to register/revoke external client token");
            throw new UnauthenticatedException("Invalid requesting auth token for service");
        }
    }

    /**
     * Revoke an auth token for an external client which is not part of Greengrass. Only authenticated EG service can
     * revoke such a token.
     * @param requestingAuthToken Auth token of the requesting service
     * @param authTokenToRevoke The auth token to revoke
     * @return true if authTokenToRevoke existed and is now removed, false if authTokenToRevoke does not exist.
     * @throws UnauthenticatedException thrown when the requestAuthToken is invalid
     */
    public boolean revokeAuthenticationTokenForExternalClient(String requestingAuthToken, String authTokenToRevoke)
            throws UnauthenticatedException {
        authenticateRequestsForExternalClient(requestingAuthToken);
        return revokeAuthenticationToken(authTokenToRevoke);
    }

    private boolean revokeAuthenticationToken(String authTokenToRevoke) {
        Topic tokenTopic = config.lookup(GreengrassService.SERVICES_NAMESPACE_TOPIC,
                AUTHENTICATION_TOKEN_LOOKUP_KEY, authTokenToRevoke);
        if (tokenTopic == null) {
            return false;
        }
        tokenTopic.remove();
        return true;
    }

    /**
     * Lookup the provided authentication token to associate it with a service (or reject it).
     * @param authenticationToken token to be looked up.
     * @return service name to which the token is associated.
     * @throws UnauthenticatedException if token is invalid or unassociated.
     */
    public String doAuthentication(String authenticationToken) throws UnauthenticatedException {
        if (authenticationToken == null) {
            throw new UnauthenticatedException("Invalid authentication token");
        }
        Topic service = config.find(GreengrassService.SERVICES_NAMESPACE_TOPIC,
                AUTHENTICATION_TOKEN_LOOKUP_KEY, authenticationToken);
        if (service == null) {
            throw new UnauthenticatedException("Authentication token not found");
        }
        return Coerce.toString(service.getOnce());
    }
}
