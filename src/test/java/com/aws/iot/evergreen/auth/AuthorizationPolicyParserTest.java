package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.aws.iot.evergreen.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class AuthorizationPolicyParserTest {

    private Configuration config;
    private final AuthorizationPolicyParser policyParser = new AuthorizationPolicyParser();

    @Mock
    private Kernel kernel;

    @Mock
    private EvergreenService testService1;

    @Mock
    private EvergreenService testService2;

    protected final Logger logger = LogManager.getLogger(this.getClass());

    @BeforeEach()
    void beforeEach() throws NoSuchFieldException {
        config = new Configuration(new Context());
        HashSet<EvergreenService> mockServiceSet = new HashSet<>();
        mockServiceSet.add(testService1);
        mockServiceSet.add(testService2);
        when(kernel.orderedDependencies()).thenReturn(mockServiceSet);
        //Make this lenient since some tests should throw an exception before parsing one or more of these service configs
        lenient().when(testService1.getConfig()).thenReturn(config.lookupTopics(
                SERVICES_NAMESPACE_TOPIC, "ServiceName"));
        lenient().when(testService2.getConfig()).thenReturn(config.lookupTopics(
                SERVICES_NAMESPACE_TOPIC, "mqtt"));
    }

    @AfterEach
    void afterEach() throws IOException {
        config.context.close();
    }

    @Test
    public void GIVEN_valid_pubsub_ACL_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub_valid.yaml")) {
            assertNotNull(inputStream);
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));


            Map<String, List<AuthorizationPolicy>> authorizationPolicyMap = policyParser
                    .parseAllAuthorizationPolicies(kernel, logger);
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
            assertThat(policy.getOperations(), containsInAnyOrder("publish", "subscribe", "unsubscribe"));
            assertThat(policy.getPrincipals(), containsInAnyOrder("ServiceName"));
            assertThat(policy.getResources(), containsInAnyOrder("/topic/1/#", "/longer/topic/example/", "*"));
        }
    }

    @Test
    public void GIVEN_valid_pubsub_ACL_without_description_or_resources_WHEN_auth_parsing_THEN_return_auth_policies() throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub_valid_optional.yaml")) {
            assertNotNull(inputStream);
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

            Map<String, List<AuthorizationPolicy>> authorizationPolicyMap = policyParser
                    .parseAllAuthorizationPolicies(kernel, logger);
            assertThat(authorizationPolicyMap.size(), equalTo(1));
            assertThat(authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).size(), equalTo(2));

            Collections.sort(authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME));

            AuthorizationPolicy policy = authorizationPolicyMap.get(PUB_SUB_SERVICE_NAME).get(0);
            assertThat(policy.getPolicyId(), equalTo("policyId1"));
            assertThat(policy.getOperations(), containsInAnyOrder("publish", "subscribe", "unsubscribe"));
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
    }

    @Test
    public void GIVEN_invalid_pubsub_yaml_file_without_operations_WHEN_auth_parsing_THEN_fail(ExtensionContext context) throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub_invalid_no_operations.yaml")) {
            assertNotNull(inputStream);
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

            ignoreExceptionOfType(context, NullPointerException.class);
            //TODO: this no longer throws an exception; we need to parse the log to check the behavior
            policyParser.parseAllAuthorizationPolicies(kernel, logger);
        }
    }

    @Test
    public void GIVEN_invalid_pubsub_yaml_file_with_invalid_fields_WHEN_auth_parsing_THEN_fail() throws Throwable {

        try (
                InputStream inputStream = getClass().getResourceAsStream("pubsub_invalid_fields.yaml")) {
            assertNotNull(inputStream);
            config.mergeMap(0, (Map) JSON.std.with(new YAMLFactory()).anyFrom(inputStream));

            //TODO: this no longer throws an exception; we need to parse the log to check the behavior
            policyParser.parseAllAuthorizationPolicies(kernel, logger);
        }
    }
}
