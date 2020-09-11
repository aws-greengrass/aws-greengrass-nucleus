package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.authorization.AuthorizationClient;
import com.aws.iot.evergreen.ipc.authorization.AuthorizationException;
import com.aws.iot.evergreen.ipc.authorization.AuthorizationResponse;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.exceptions.UnauthorizedException;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.iot.evergreen.ipc.AuthenticationHandler.AUTHENTICATION_TOKEN_LOOKUP_KEY;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
class IPCAuthorizationTest {

    @TempDir
    static Path tempRootDir;
    private static Kernel kernel;
    private IPCClient client;
    private AuthorizationClient authorizationClient;

    @BeforeEach
    void beforeEach(ExtensionContext context) throws Exception  {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");

        kernel = prepareKernelFromConfigFile("ipc.yaml", TEST_SERVICE_NAME, this.getClass());
        KernelIPCClientConfig config = getIPCConfigForService(TEST_SERVICE_NAME, kernel);
        client = new IPCClientImpl(config);
        authorizationClient = new AuthorizationClient(client);
    }

    @BeforeAll
    static void startKernel() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }

    @AfterEach
    void afterEach() throws IOException {
        client.disconnect();
        kernel.shutdown();
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
        assertEquals("com.aws.iot.evergreen.ipc.exceptions.UnauthorizedException: Unable to authorize request",
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
}
