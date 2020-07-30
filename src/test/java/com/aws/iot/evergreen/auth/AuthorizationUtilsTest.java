package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class AuthorizationUtilsTest {

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
                List<AuthorizationPolicy> authorizationPolicyList = AuthorizationUtils
                    .parseAuthorizationPolicy(pubsub);
                assertEquals(1, authorizationPolicyList.size());

                AuthorizationPolicy policy = authorizationPolicyList.get(0);
                assertThat("policyId1", equalTo(policy.getPolicyId()));
                assertThat("access to pubsub topics", equalTo(policy.getPolicyDescription()));
                assertThat(policy.getOperations(), containsInAnyOrder("publish", "subscribe"));
                assertThat(policy.getPrincipals(), containsInAnyOrder("ServiceName", "mqtt"));
                assertThat(policy.getResources(), containsInAnyOrder("/topic/1/#", "/longer/topic/example/", "*"));
        }
    }

    @Test
    public void GIVEN_valid_pubsub_ACL_without_description_or_resources_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub.yaml")) {
                assertNotNull(inputStream);
                config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

                Topics pubsub = config.findTopics("services").findTopics("pubsub_no_description");
                List<AuthorizationPolicy> authorizationPolicyList = AuthorizationUtils
                        .parseAuthorizationPolicy(pubsub);
                assertEquals(1, authorizationPolicyList.size());

                AuthorizationPolicy policy = authorizationPolicyList.get(0);
                assertThat("policyId1", equalTo(policy.getPolicyId()));
                assertThat(policy.getOperations(), containsInAnyOrder("publish", "subscribe"));
                assertThat(policy.getPrincipals(), containsInAnyOrder("ServiceName", "mqtt"));
                assertThat(policy.getPolicyDescription(), Matchers.nullValue());
                assertThat(policy.getResources(), Matchers.nullValue());
        }
    }

    @Test
    public void GIVEN_invalid_pubsub_yaml_file_without_operations_WHEN_auth_parsing_THEN_fail() throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub.yaml")) {
            assertNotNull(inputStream);
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

            Topics pubsub = config.findTopics("services").findTopics("pubsub_no_operations");
            assertThrows(NullPointerException.class, () -> AuthorizationUtils
                    .parseAuthorizationPolicy(pubsub));
        }
    }
}
