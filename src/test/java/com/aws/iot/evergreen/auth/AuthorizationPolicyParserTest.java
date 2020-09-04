package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.logging.impl.Slf4jLogAdapter;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class AuthorizationPolicyParserTest {

    private final AuthorizationPolicyParser policyParser = new AuthorizationPolicyParser();

    @Mock
    private Kernel kernel;

    @Mock
    private Topics mockPubSub;

    private Configuration realConfig;

    private CountDownLatch logReceived;

    private Consumer<EvergreenStructuredLogMessage> logListener;

    protected final Logger logger = LogManager.getLogger(this.getClass());

    private void readConfig(String filename) throws IOException {
        realConfig = new Configuration(new Context());
        try (InputStream inputStream = getClass().getResourceAsStream(filename)) {
            assertNotNull(inputStream);
            realConfig.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));
        }
        when(kernel.getConfig()).thenReturn(realConfig);
        when(kernel.findServiceTopic(PUB_SUB_SERVICE_NAME)).thenReturn(mockPubSub);
    }

    @AfterEach
    void afterEach() throws IOException {
        realConfig.context.close();
    }

    @Test
    public void GIVEN_valid_pubsub_ACL_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

        readConfig("pubsub_valid.yaml");
        Map<String, List<AuthorizationPolicy>> authorizationPolicyMap = policyParser
                .parseAllAuthorizationPolicies(kernel);
        assertThat(authorizationPolicyMap.size(), equalTo(1));
        assertThat(authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).size(), equalTo(2));

        Collections.sort(authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME));

        AuthorizationPolicy policy2 = authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).get(1);
        assertThat(policy2.getPolicyId(), equalTo("policyId2"));
        assertThat(policy2.getPolicyDescription(), equalTo("access to pubsub topics"));
        assertThat(policy2.getOperations(), containsInAnyOrder("publish", "subscribe"));
        assertThat(policy2.getPrincipals(), containsInAnyOrder("mqtt"));
        assertThat(policy2.getResources(), containsInAnyOrder("/topic/1/#", "/longer/topic/example/"));

        AuthorizationPolicy policy = authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).get(0);
        assertThat(policy.getPolicyId(), equalTo("policyId1"));
        assertThat(policy.getPolicyDescription(), equalTo("access to pubsub topics"));
        assertThat(policy.getOperations(), containsInAnyOrder("publish", "subscribe"));
        assertThat(policy.getPrincipals(), containsInAnyOrder("ServiceName"));
        assertThat(policy.getResources(), containsInAnyOrder("/topic/1/#", "/longer/topic/example/", "*"));
    }

    @Test
    public void GIVEN_valid_pubsub_ACL_without_description_or_resources_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

        readConfig("pubsub_valid_optional.yaml");

        Map<String, List<AuthorizationPolicy>> authorizationPolicyMap = policyParser
                .parseAllAuthorizationPolicies(kernel);
        assertThat(authorizationPolicyMap.size(), equalTo(1));
        assertThat(authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).size(), equalTo(2));

        Collections.sort(authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME));

        AuthorizationPolicy policy = authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).get(0);
        assertThat(policy.getPolicyId(), equalTo("policyId1"));
        assertThat(policy.getOperations(), containsInAnyOrder("publish", "subscribe"));
        assertThat(policy.getPrincipals(), containsInAnyOrder("ServiceName"));
        assertThat(policy.getPolicyDescription(), Matchers.nullValue());
        assertThat(policy.getResources(), Matchers.nullValue());

        AuthorizationPolicy policy2 = authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).get(1);
        assertThat(policy2.getPolicyId(), equalTo("policyId2"));
        assertThat(policy2.getOperations(), containsInAnyOrder("publish", "subscribe"));
        assertThat(policy2.getPrincipals(), containsInAnyOrder("mqtt"));
        assertThat(policy2.getPolicyDescription(), Matchers.nullValue());
        assertThat(policy2.getResources(), Matchers.nullValue());
    }

    @Test
    public void GIVEN_invalid_pubsub_yaml_file_without_operations_WHEN_auth_parsing_THEN_fail(ExtensionContext context) throws Throwable {

        readConfig("pubsub_invalid_no_operations.yaml");
        try {
            logReceived = new CountDownLatch(1);
            logListener = m -> {
                if ("load-authorization-missing-policy-component-operations".equals(m.getEventType())) {
                    logReceived.countDown();
                }
            };
            Slf4jLogAdapter.addGlobalListener(logListener);
            policyParser.parseAllAuthorizationPolicies(kernel);
            assertTrue(logReceived.await(5, TimeUnit.SECONDS));
        } finally {
            Slf4jLogAdapter.removeGlobalListener(logListener);
        }
    }

    @Test
    public void GIVEN_invalid_pubsub_yaml_file_with_invalid_fields_WHEN_auth_parsing_THEN_fail() throws Throwable {
        readConfig("pubsub_invalid_fields.yaml");
        try {
            logReceived = new CountDownLatch(1);
            logListener = m -> {
                if ("load-authorization-config-unknown-policy-key".equals(m.getEventType())) {
                    logReceived.countDown();
                }
            };
            Slf4jLogAdapter.addGlobalListener(logListener);
            policyParser.parseAllAuthorizationPolicies(kernel);
            assertTrue(logReceived.await(5, TimeUnit.SECONDS));
        } finally {
            Slf4jLogAdapter.removeGlobalListener(logListener);
        }
    }
}
