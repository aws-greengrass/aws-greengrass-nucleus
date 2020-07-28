package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class AuthorizationPolicyTest {

    private Configuration config;

    @BeforeEach()
    void beforeEach() {
        config = new Configuration(new Context());
    }

    @AfterEach
    void afterEach() throws IOException {
        config.context.close();
    }

    @Test
    public void GIVEN_valid_pubsub_ACL_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub.yaml")) {
                assertNotNull(inputStream);
                config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

                Topics pubsub = config.findTopics("services").findTopics("pubsub").findTopics();
                List<AuthorizationPolicy> authorizationPolicyList = AuthorizationPolicy
                    .parseAuthorizationPolicy(pubsub);
                assertEquals(1, authorizationPolicyList.size());

                AuthorizationPolicy policy = authorizationPolicyList.get(0);
                assertEquals("policyId1", policy.getPolicyId());
                assertEquals("access to pubsub topics", policy.getPolicyDescription());
                assertTrue(policy.getOperations().contains("publish"));
                assertTrue(policy.getOperations().contains("subscribe"));
                assertTrue(policy.getPrincipals().contains("ServiceName"));
                assertTrue(policy.getPrincipals().contains("mqtt"));
                assertTrue(policy.getResources().contains("/topic/1/#"));
                assertTrue(policy.getResources().contains("/longer/topic/example/"));
                assertTrue(policy.getResources().contains("*"));
        }
    }

    @Test
    public void GIVEN_valid_pubsub_ACL_without_description_or_resources_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub.yaml")) {
                assertNotNull(inputStream);
                config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

                Topics pubsub = config.findTopics("services").findTopics("pubsub_no_description");
                List<AuthorizationPolicy> authorizationPolicyList = AuthorizationPolicy
                        .parseAuthorizationPolicy(pubsub);
                assertEquals(1, authorizationPolicyList.size());

                AuthorizationPolicy policy = authorizationPolicyList.get(0);
                assertEquals("policyId1", policy.getPolicyId());
                assertNull(policy.getPolicyDescription());
                assertTrue(policy.getOperations().contains("publish"));
                assertTrue(policy.getOperations().contains("subscribe"));
                assertTrue(policy.getPrincipals().contains("ServiceName"));
                assertTrue(policy.getPrincipals().contains("mqtt"));
                assertNull(policy.getResources());
        }
    }

    @Test
    public void GIVEN_invalid_pubsub_yaml_file_without_operations_WHEN_auth_parsing_THEN_fail() throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub.yaml")) {
            assertNotNull(inputStream);
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

            Topics pubsub = config.findTopics("services").findTopics("pubsub_no_operations");
            assertThrows(NullPointerException.class, () -> AuthorizationPolicy
                    .parseAuthorizationPolicy(pubsub));
        }
    }
}
