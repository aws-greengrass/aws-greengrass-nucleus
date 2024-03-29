/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.testcommons.testutilities.UniqueRootPathExtension;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.aws.greengrass.model.ValidateAuthorizationTokenRequest;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getEventStreamRpcConnection;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.ipc.AuthenticationHandler.AUTHENTICATION_TOKEN_LOOKUP_KEY;
import static com.aws.greengrass.ipc.AuthenticationHandler.registerAuthenticationToken;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, UniqueRootPathExtension.class})
class IPCAuthorizationTest extends BaseITCase {

    private static Kernel kernel;
    private static EventStreamRPCConnection clientConnection;
    private static SocketOptions socketOptions;

    @BeforeAll
    static void beforeAll() throws Exception {
        kernel = prepareKernelFromConfigFile("ipc.yaml", IPCAuthorizationTest.class, TEST_SERVICE_NAME);
        socketOptions = TestUtils.getSocketOptionsForIPC();
        clientConnection = getEventStreamRpcConnection(kernel, TEST_SERVICE_NAME);
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
    void beforeEach(ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
    }

    @Test
    void GIVEN_authorizationClient_WHEN_null_token_provided_THEN_Fail() {
        ExecutionException executionException = assertThrows(ExecutionException.class,
                () -> validateAuthorizationToken(null));
        assertEquals(UnauthorizedError.class, executionException.getCause().getClass());
    }

    @Test
    void GIVEN_authorizationClient_WHEN_empty_token_provided_THEN_Fail() {
        ExecutionException executionException = assertThrows(ExecutionException.class,
                () -> validateAuthorizationToken(""));
        assertEquals(UnauthorizedError.class, executionException.getCause().getClass());
    }

    @Test
    void GIVEN_authorizationClient_WHEN_invalid_token_provided_THEN_Fail() {
        ExecutionException executionException = assertThrows(ExecutionException.class,
                () -> validateAuthorizationToken("invalidToken"));
        assertEquals(UnauthorizedError.class, executionException.getCause().getClass());
    }

    @Test
    void GIVEN_authorizationClient_WHEN_valid_token_provided_THEN_succeeds() {
        //Grab a real, randomly assigned auth token for an existing service
        Topics authTokensArray = kernel.findServiceTopic(AUTHENTICATION_TOKEN_LOOKUP_KEY);
        Topic authTokenTopic = (Topic) authTokensArray.children.values().toArray()[0];

        ExecutionException executionException = assertThrows(ExecutionException.class,
                () -> validateAuthorizationToken(authTokenTopic.getName()));
        assertEquals(UnauthorizedError.class, executionException.getCause().getClass());

    }

    @Test
    void GIVEN_authorizationClient_WHEN_valid_ondemand_lambda_token_provided_THEN_succeeds() {
        GreengrassService mockService = mock(GreengrassService.class);
        when(mockService.getServiceName()).thenReturn("ABCService");
        lenient().when(mockService.getName()).thenReturn("ABCService#1"); // Pretend to be instance #1 of a lambda
        when(mockService.getServiceConfig()).thenReturn(kernel.findServiceTopic("ServiceName"));
        when(mockService.getPrivateConfig()).thenReturn(kernel.findServiceTopic("ServiceName")
                .lookupTopics(PRIVATE_STORE_NAMESPACE_TOPIC));
        registerAuthenticationToken(mockService);
        Topics authTokensArray = kernel.findServiceTopic(AUTHENTICATION_TOKEN_LOOKUP_KEY);
        boolean found = false;
        for (Node node : authTokensArray) {
            if ("ABCService".equals(Coerce.toString(node))) {
                if (found) {
                    fail("Duplicate entry!");
                }
                found = true;
            }
        }
        assertTrue(found);
    }

    private boolean validateAuthorizationToken(String token) throws Exception {
        ValidateAuthorizationTokenRequest request = new ValidateAuthorizationTokenRequest();
        request.setToken(token);
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        return greengrassCoreIPCClient.validateAuthorizationToken(request, Optional.empty()).getResponse()
                .get(30, TimeUnit.SECONDS).isIsValid();
    }
}
