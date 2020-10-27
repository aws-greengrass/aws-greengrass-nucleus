/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.testcommons.testutilities.UniqueRootPathExtension;
import com.aws.greengrass.ipc.IPCClient;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.authorization.AuthorizationClient;
import com.aws.greengrass.ipc.authorization.AuthorizationException;
import com.aws.greengrass.ipc.authorization.AuthorizationResponse;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.exceptions.UnauthorizedException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ValidateAuthorizationTokenRequest;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.authorization.AuthorizationIPCAgent.STREAM_MANAGER_SERVICE_NAME;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.ipc.AuthenticationHandler.AUTHENTICATION_TOKEN_LOOKUP_KEY;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({GGExtension.class, UniqueRootPathExtension.class})
class IPCAuthorizationTest {

    private static Kernel kernel;
    private IPCClient client;
    private AuthorizationClient authorizationClient;
    private static EventStreamRPCConnection clientConnection;
    private static SocketOptions socketOptions;

    @BeforeAll
    static void beforeAll() throws InterruptedException {
        kernel = prepareKernelFromConfigFile("ipc.yaml", IPCAuthorizationTest.class, TEST_SERVICE_NAME);
    }

    @AfterAll
    static void afterAll() {
        if (clientConnection != null) {
            clientConnection.disconnect();
        }
        if (socketOptions != null) {
            socketOptions.close();
        }
        kernel.shutdown();
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) throws Exception  {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        KernelIPCClientConfig config = getIPCConfigForService(TEST_SERVICE_NAME, kernel);
        client = new IPCClientImpl(config);
        authorizationClient = new AuthorizationClient(client);
    }

    @AfterEach
    void afterEach() throws IOException {
        client.disconnect();
    }

    @Test
    void GIVEN_authorizationClient_WHEN_null_token_provided_THEN_Fail(ExtensionContext context) {

        ignoreExceptionOfType(context, AuthorizationException.class);
        AuthorizationException e = assertThrows(AuthorizationException.class, () ->
                authorizationClient.validateToken(null));
        assertEquals("Provided auth token is null", e.getMessage());
    }

    @Test
    void GIVEN_authorizationClient_WHEN_empty_token_provided_THEN_Fail(ExtensionContext context) {

        ignoreExceptionOfType(context, AuthorizationException.class);
        AuthorizationException e = assertThrows(AuthorizationException.class, () ->
                authorizationClient.validateToken(""));
        assertEquals("Provided auth token is empty", e.getMessage());
    }

    @Test
    void GIVEN_authorizationClient_WHEN_invalid_token_provided_THEN_Fail(ExtensionContext context) {

        ignoreExceptionOfType(context, UnauthorizedException.class);
        AuthorizationException e = assertThrows(AuthorizationException.class, () ->
                authorizationClient.validateToken("invalidToken"));
        assertEquals("com.aws.greengrass.ipc.exceptions.UnauthorizedException: Unable to authorize request",
                e.getMessage());
    }

    @Test
    void GIVEN_authorizationClient_WHEN_valid_token_provided_THEN_succeeds() throws AuthorizationException {
        //Grab a real, randomly assigned auth token for an existing service
        Topics authTokensArray = kernel.findServiceTopic(AUTHENTICATION_TOKEN_LOOKUP_KEY);
        Topic authTokenTopic = (Topic) authTokensArray.children.values().toArray()[0];

        AuthorizationResponse response = authorizationClient.validateToken(authTokenTopic.getName());
        assertTrue(response.isAuthorized());
    }

    @Test
    void GIVEN_authorizationEventStreamClient_valid_token_provided_THEN_succeeds() throws Exception {
        Topics servicePrivateConfig = kernel.getConfig()
                .findTopics(SERVICES_NAMESPACE_TOPIC, STREAM_MANAGER_SERVICE_NAME, PRIVATE_STORE_NAMESPACE_TOPIC);
        String authToken = Coerce.toString(servicePrivateConfig.find(SERVICE_UNIQUE_ID_KEY));
        socketOptions = TestUtils.getSocketOptionsForIPC();
        clientConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);

        ValidateAuthorizationTokenRequest request = new ValidateAuthorizationTokenRequest();
        request.setToken(authToken);

        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        assertTrue(greengrassCoreIPCClient.validateAuthorizationToken(request, Optional.empty()).getResponse()
                .get(30, TimeUnit.SECONDS).isIsValid());
    }
}
