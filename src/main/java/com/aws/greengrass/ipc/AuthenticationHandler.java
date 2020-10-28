/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.ipc.modules.CLIService;
import com.aws.greengrass.ipc.services.authentication.AuthenticationRequest;
import com.aws.greengrass.ipc.services.authentication.AuthenticationResponse;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.common.IPCUtil;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import io.netty.channel.ChannelHandlerContext;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.net.SocketAddress;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.common.ResponseHelper.sendResponse;

@AllArgsConstructor
@NoArgsConstructor
public class AuthenticationHandler implements InjectionActions {
    public static final String AUTHENTICATION_TOKEN_LOOKUP_KEY = "_AUTHENTICATION_TOKENS";
    public static final String SERVICE_UNIQUE_ID_KEY = "_UID";
    public static final int AUTHENTICATION_API_VERSION = 1;
    private static final Logger logger = LogManager.getLogger(AuthenticationHandler.class);

    @Inject
    private Configuration config;
    @Inject
    private IPCRouter router;

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
     * Authenticate the incoming message and return a RequestContext if successful.
     *
     * @param message       incoming message frame to be validated.
     * @param remoteAddress remote address the client is connected from
     * @return RequestContext containing the server name if validated.
     * @throws UnauthenticatedException thrown if not authorized, or any other error happens.
     */
    public ConnectionContext doAuthentication(FrameReader.Message message, SocketAddress remoteAddress)
            throws UnauthenticatedException {

        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        AuthenticationRequest authenticationRequest;
        try {
            authenticationRequest = IPCUtil.decode(applicationMessage.getPayload(), AuthenticationRequest.class);
        } catch (IOException e) {
            throw new UnauthenticatedException("Fail to decode Authentication message", e);
        }

        String serviceName = doAuthentication(authenticationRequest.getAuthenticationToken());
        return new ConnectionContext(serviceName, remoteAddress, router);
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

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    void handleAuthentication(ChannelHandlerContext ctx, FrameReader.MessageFrame message) throws IOException {
        if (message.destination == BuiltInServiceDestinationCode.AUTHENTICATION.getValue()) {
            try {
                ConnectionContext context = doAuthentication(message.message, ctx.channel().remoteAddress());
                ctx.channel().attr(IPCChannelHandler.CONNECTION_CONTEXT_KEY).set(context);
                logger.atInfo().setEventType("ipc-client-authenticated").addKeyValue("clientContext", context).log();

                router.clientConnected(context, ctx.channel());
                AuthenticationResponse authenticationResponse =
                        AuthenticationResponse.builder().serviceName(context.getServiceName())
                                .clientId(context.getClientId())
                                .build();
                ApplicationMessage applicationMessage =
                        ApplicationMessage.builder().version(AUTHENTICATION_API_VERSION)
                                .payload(IPCUtil.encode(authenticationResponse))
                                .build();
                sendResponse(new FrameReader.Message(applicationMessage.toByteArray()), message.requestId,
                        message.destination, ctx, false);
            } catch (Throwable t) {
                logger.atError().setEventType("ipc-client-authentication-error").setCause(t)
                        .addKeyValue("clientAddress", ctx.channel().remoteAddress()).log();
                AuthenticationResponse authenticationResponse =
                        AuthenticationResponse.builder().errorMessage("Error while authenticating client").build();
                ApplicationMessage applicationMessage =
                        ApplicationMessage.builder().version(AUTHENTICATION_API_VERSION).payload(IPCUtil
                                .encode(authenticationResponse))
                                .build();
                sendResponse(new FrameReader.Message(applicationMessage.toByteArray()), message.requestId,
                        message.destination, ctx, true);
            }
        } else {
            logger.atError().setEventType("ipc-client-authentication-error")
                    .addKeyValue("clientAddress", ctx.channel().remoteAddress())
                    .addKeyValue("destination", message.destination)
                    .log("First request from client should be destined for Authentication");
            AuthenticationResponse authenticationResponse =
                    AuthenticationResponse.builder().errorMessage("Error while authenticating client").build();
            ApplicationMessage applicationMessage =
                    ApplicationMessage.builder().version(AUTHENTICATION_API_VERSION).payload(IPCUtil
                            .encode(authenticationResponse))
                            .build();
            sendResponse(new FrameReader.Message(applicationMessage.toByteArray()), message.requestId,
                    message.destination, ctx, true);
        }
    }
}
