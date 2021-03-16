/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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

import static com.aws.greengrass.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class AuthorizationPolicyParserTest {

    private final static String TEST_COMPONENT = "testComponent";
    private final AuthorizationPolicyParser policyParser = new AuthorizationPolicyParser();

    @Mock
    private Kernel kernel;

    private Configuration realConfig;

    private CountDownLatch logReceived;

    private Consumer<GreengrassLogMessage> logListener;

    protected final Logger logger = LogManager.getLogger(this.getClass());

    private void readConfig(String filename) throws IOException {
        realConfig = new Configuration(new Context());
        try (InputStream inputStream = getClass().getResourceAsStream(filename)) {
            assertNotNull(inputStream);
            realConfig.mergeMap(0, new YAMLMapper().readValue(inputStream, Map.class));
        }
        when(kernel.getConfig()).thenReturn(realConfig);
    }

    @AfterEach
    void afterEach() throws IOException {
        realConfig.context.close();
    }

    @Test
    void GIVEN_valid_pubsub_ACL_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

        readConfig("pubsub_valid.yaml");
        Map<String, List<AuthorizationPolicy>> authorizationPolicyMap = policyParser
                .parseAllAuthorizationPolicies(kernel);
        // We have total of 2 destination components
        assertThat(authorizationPolicyMap.size(), equalTo(2));
        // pub sub has total 3 policies
        assertThat(authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).size(), equalTo(3));

        Collections.sort(authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME));

        AuthorizationPolicy policy1 = authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).get(0);
        assertThat(policy1.getPolicyId(), equalTo("policyId1"));
        assertThat(policy1.getPolicyDescription(), equalTo("access to pubsub topics 1"));
        assertThat(policy1.getOperations(), containsInAnyOrder("publish", "subscribe"));
        assertThat(policy1.getPrincipals(), containsInAnyOrder("mqtt"));
        assertThat(policy1.getResources(), containsInAnyOrder("/topic/1/#", "/longer/topic/example/"));

        AuthorizationPolicy policy2 = authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).get(1);
        assertThat(policy2.getPolicyId(), equalTo("policyId2"));
        assertThat(policy2.getPolicyDescription(), equalTo("access to pubsub topics 2"));
        assertThat(policy2.getOperations(), containsInAnyOrder("publish"));
        assertThat(policy2.getPrincipals(), containsInAnyOrder("mqtt"));
        assertThat(policy2.getResources(), containsInAnyOrder("/publishOnlyTopic"));

        AuthorizationPolicy policy3 = authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).get(2);
        assertThat(policy3.getPolicyId(), equalTo("policyId4"));
        assertThat(policy3.getPolicyDescription(), equalTo("access to pubsub topics 4"));
        assertThat(policy3.getOperations(), containsInAnyOrder("publish", "subscribe"));
        assertThat(policy3.getPrincipals(), containsInAnyOrder("ServiceName"));
        assertThat(policy3.getResources(), containsInAnyOrder("/topic/1/#", "/longer/topic/example/", "*"));

        AuthorizationPolicy secretPolicy = authorizationPolicyMap.get(TEST_COMPONENT).get(0);
        assertThat(secretPolicy.getPolicyId(), equalTo("policyId3"));
        assertThat(secretPolicy.getPolicyDescription(), equalTo("access to secrets"));
        assertThat(secretPolicy.getOperations(), containsInAnyOrder("getsecret"));
        assertThat(secretPolicy.getPrincipals(), containsInAnyOrder("mqtt"));
        assertThat(secretPolicy.getResources(), containsInAnyOrder("secret1"));
    }

    @Test
    void GIVEN_valid_pubsub_ACL_without_description_or_resources_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

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
    void GIVEN_invalid_pubsub_yaml_file_without_operations_WHEN_auth_parsing_THEN_fail() throws Throwable {

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
    void GIVEN_invalid_pubsub_yaml_file_with_invalid_fields_WHEN_auth_parsing_THEN_fail(ExtensionContext context)
            throws Throwable {
        ignoreExceptionOfType(context, IllegalArgumentException.class);
        readConfig("pubsub_invalid_fields.yaml");
        try {
            logReceived = new CountDownLatch(1);
            logListener = m -> {
                if ("load-authorization-config-deserialization-error".equals(m.getEventType())) {
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
