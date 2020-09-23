/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class AuthorizationHandlerTest {

    @Mock
    private Kernel mockKernel;

    @Mock
    private Topics mockTopics;

    private AuthorizationModule authModule;

    private final AuthorizationPolicyParser policyParser = new AuthorizationPolicyParser();

    private CountDownLatch logReceived;

    private Consumer<GreengrassLogMessage> logListener;

    private AuthorizationPolicy getAuthZPolicy() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }


    private List<AuthorizationPolicy> getAuthZPolicyWithDuplicateId() {
        AuthorizationPolicy policy1 = AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
        AuthorizationPolicy policy2 = AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
        return Arrays.asList(policy1, policy2);
    }

    private AuthorizationPolicy getAuthZPolicyB() {
        return AuthorizationPolicy.builder()
                .policyId("Id2")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("ServiceC", "ServiceD")))
                .operations(new HashSet(Arrays.asList("OpD", "OpE")))
                .build();
    }

    private AuthorizationPolicy getStarOperationsAuthZPolicy() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("*")))
                .build();
    }

    private AuthorizationPolicy getStarResourceAuthZPolicy() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("compA")))
                .operations(new HashSet(Arrays.asList("OpA")))
                .resources(new HashSet(Arrays.asList("*")))
                .build();
    }

    private AuthorizationPolicy getStarSourcesAuthZPolicy() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("*")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }

    private AuthorizationPolicy getAuthZPolicyWithEmptyPrincipal() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principals(new HashSet())
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }

    private AuthorizationPolicy getAuthZPolicyWithEmptyOp() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("*")))
                .operations(new HashSet())
                .build();
    }

    private AuthorizationPolicy getAuthZPolicyWithEmptyPolicyId() {
        return AuthorizationPolicy.builder()
                .policyId("")
                .policyDescription("Test policy")
                .principals(new HashSet(Arrays.asList("*")))
                .operations(new HashSet())
                .build();
    }

    @BeforeEach
    void beforeEach() {
        when(mockKernel.getConfig()).thenReturn(new Configuration(new Context()));
        authModule = new AuthorizationModule();
    }

    @AfterEach
    void afterEach() throws IOException {
        mockKernel.getConfig().context.close();
        Slf4jLogAdapter.removeGlobalListener(logListener);
    }

    private void setupLogListener(String logEventType) {
        Slf4jLogAdapter.removeGlobalListener(logListener);
        logReceived = new CountDownLatch(1);
        logListener = m -> {
            if (logEventType.equals(m.getEventType())) {
                logReceived.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);
    }


    @Test
    void GIVEN_AuthZ_handler_WHEN_authz_policy_with_invalid_component_THEN_load_fails(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, AuthorizationException.class);
        setupLogListener("load-authorization-config-invalid-component");

        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel, authModule, policyParser);
        authorizationHandler.loadAuthorizationPolicies(null, Collections.singletonList(getAuthZPolicy()),
                false);

        assertTrue(logReceived.await(5, TimeUnit.SECONDS));

        setupLogListener("load-authorization-config-invalid-component");
        authorizationHandler.loadAuthorizationPolicies("", Collections.singletonList(getAuthZPolicy()),
                false);
        assertTrue(logReceived.await(5, TimeUnit.SECONDS));
    }

    //TODO: Add component registration logic back in along with this test:
    // https://issues-iad.amazon.com/issues/V234938383
//    @Test
//    void GIVEN_AuthZ_handler_WHEN_component_registered_twice_THEN_errors() throws AuthorizationException {
//        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel);
//        final Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
//        authorizationHandler.registerComponent("ServiceA", serviceOps);
//
//        authorizationHandler.registerComponent("ServiceA", serviceOps);
//        final Set<String> serviceOps_2 = new HashSet<>(Arrays.asList("OpA"));
//        authorizationHandler.registerComponent("ServiceA", serviceOps_2);

//        // Another component can be registered
//        authorizationHandler.registerComponent("ServiceB", serviceOps_2);

//        //assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceC",
//        //Permission.builder().principal("*").operation("*").resource(null).build()));
//    }

    @ParameterizedTest
    @NullAndEmptySource
    void GIVEN_AuthZ_handler_WHEN_component_registered_with_empty_name_THEN_errors(String componentName) {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel, authModule, policyParser);
        final Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.registerComponent(componentName,
                serviceOps));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_component_registered_without_operation_THEN_errors() throws AuthorizationException {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel, authModule, policyParser);
        final Set<String> emptyOps = new HashSet<>();
        assertThrows(AuthorizationException.class, () -> authorizationHandler.registerComponent(
                "ServiceA", emptyOps));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_component_registered_THEN_auth_works() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel, authModule, policyParser);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authorizationHandler.registerComponent("ServiceA", serviceOps);

        Set<String> serviceOpsB = new HashSet<>(Arrays.asList("OpD", "OpE"));
        authorizationHandler.registerComponent("ServiceB", serviceOpsB);

        authorizationHandler.loadAuthorizationPolicies("ServiceA",
                Collections.singletonList(getAuthZPolicy()), false);
        authorizationHandler.loadAuthorizationPolicies("ServiceB",
                Collections.singletonList(getAuthZPolicyB()), false);

        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpC").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compB").operation("OpC").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compB").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compB").operation("OpA").resource(null).build()));

        assertTrue(authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("ServiceD").operation("OpD").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("ServiceD").operation("OpE").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("ServiceC").operation("OpD").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("ServiceC").operation("OpE").resource(null).build()));

        // Components are not allowed to be accessed from other principals
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compC").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compA").operation("OpD").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compA").operation("OpE").resource(null).build()));

        // Components are not allowed to be accessed for non allowed ops
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compB").operation("Op").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("Op").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("ServiceD").operation("Op").resource(null).build()));

        // Components are not allowed to be accessed from * principal
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("*").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("random").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("*").operation("OpD").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("random").operation("OpD").resource(null).build()));

        // Components are not allowed to be accessed for * operation
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("*").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("ServiceD").operation("*").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_component_registered_THEN_auth_lookup_for_star_operation_works() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel, authModule, policyParser);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authorizationHandler.registerComponent("ServiceA", serviceOps);
        authorizationHandler.registerComponent("ServiceB", serviceOps);

        AuthorizationPolicy policy = getStarOperationsAuthZPolicy();
        authorizationHandler.loadAuthorizationPolicies("ServiceA", Collections.singletonList(policy),
                false);
        // All registered Operations are allowed now
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpC").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compB").operation("OpC").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compB").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compB").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compB").operation("*").resource(null).build()));

        // random destination should not be allowed
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compC").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compA").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compA").operation("OpB").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compA").operation("OpC").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compB").operation("OpC").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compB").operation("OpB").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compB").operation("OpA").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_service_registered_THEN_auth_lookup_for_star_resource_works() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel, authModule, policyParser);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA"));
        authorizationHandler.registerComponent("ServiceA", serviceOps);

        AuthorizationPolicy policy = getStarResourceAuthZPolicy();
        authorizationHandler.loadAuthorizationPolicies("ServiceA", Collections.singletonList(policy),
                false);
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpA").resource("*").build()));

        // A random string works
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpA").resource("randomString").build()));

        // null resource be allowed as it will pass * check
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpA").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_component_registered_THEN_auth_lookup_for_star_source_works() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel, authModule, policyParser);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authorizationHandler.registerComponent("ServiceA", serviceOps);
        authorizationHandler.registerComponent("ServiceB", serviceOps);

        AuthorizationPolicy policy = getStarSourcesAuthZPolicy();
        authorizationHandler.loadAuthorizationPolicies("ServiceA", Collections.singletonList(policy),
                false);
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpC").resource(null).build()));
        // A non allowed Op is denied
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compA").operation("OpD").resource(null).build()));

        // Random Sources should be allowed now
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("*").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("random").operation("OpA").resource(null).build()));

        // Random destination should not be allowed
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principal("compC").operation("*").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compA").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compA").operation("OpB").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("*").operation("OpC").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compB").operation("OpC").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("compB").operation("OpB").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principal("*").operation("*").resource(null).build()));
    }

    @Test
    void GIVEN_authZ_handler_WHEN_loaded_incorrect_config_THEN_load_fails(ExtensionContext context)
            throws InterruptedException {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel, authModule, policyParser);

        ignoreExceptionOfType(context, AuthorizationException.class);
        setupLogListener("load-authorization-config-invalid-component");

        // invalid component fails
        authorizationHandler
                .loadAuthorizationPolicies("",
                        Collections.singletonList(getAuthZPolicy()), false);
        assertTrue(logReceived.await(5, TimeUnit.SECONDS));

        setupLogListener("load-authorization-config-invalid-component");
        authorizationHandler.loadAuthorizationPolicies(
                null,
                Collections.singletonList(getAuthZPolicy()), false);
        assertTrue(logReceived.await(5, TimeUnit.SECONDS));

        //TODO:Add component registration logic back in along with this assertion:
        // https://issues-iad.amazon.com/issues/V234938383
//        setupLogListener("load-authorization-config-invalid-component");
//        authorizationHandler.loadAuthorizationPolicies(
//                "ServiceA",
//                Collections.singletonList(getAuthZPolicy()), false);
//        assertTrue(logReceived.await(5, TimeUnit.SECONDS));
//
//        // register the component
//        authorizationHandler.registerComponent("ServiceA", new HashSet(Arrays.asList("Op")));

        // Empty principal should fail to load now
        setupLogListener("load-authorization-config-invalid-principal");
        authorizationHandler.loadAuthorizationPolicies(
                "ServiceA",
                Collections.singletonList(getAuthZPolicyWithEmptyPrincipal()), false);
        assertTrue(logReceived.await(5, TimeUnit.SECONDS));

        // Now let the mock return null
        when(mockKernel.findServiceTopic(anyString())).thenReturn(null);
        // When kernel cannot identify a principal component then load fails
        setupLogListener("load-authorization-config-invalid-principal");
        authorizationHandler.loadAuthorizationPolicies(
                "ServiceA",
                Collections.singletonList(getAuthZPolicy()), false);
        assertTrue(logReceived.await(5, TimeUnit.SECONDS));

        // Now let the mock return mock topics
        //when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        // Ops which are not registered should fail to load
        //TODO:Add component registration logic back in along with this assertion :
        // https://issues-iad.amazon.com/issues/V234938383
        //exception = assertThrows(AuthorizationException.class, () -> authorizationHandler.loadAuthorizationPolicies("ServiceA",
        //Collections.singletonList(getAuthZPolicy())));
        //assertTrue(exception.getMessage().contains("Operation not registered"));

        // Empty operations should fail to load
        setupLogListener("load-authorization-config-invalid-operation");
        authorizationHandler.loadAuthorizationPolicies(
                "ServiceA",
                Collections.singletonList(getAuthZPolicyWithEmptyOp()), false);
        assertTrue(logReceived.await(5, TimeUnit.SECONDS));

        // duplicate policyId should fails
        setupLogListener("load-authorization-config-invalid-policy");
        authorizationHandler.loadAuthorizationPolicies("ServiceA",
                getAuthZPolicyWithDuplicateId(), false);
        assertTrue(logReceived.await(5, TimeUnit.SECONDS));

        // empty policy Id should fail
        setupLogListener("load-authorization-config-invalid-policy");
        authorizationHandler.loadAuthorizationPolicies("ServiceA",
                Collections.singletonList(getAuthZPolicyWithEmptyPolicyId()), false);
        assertTrue(logReceived.await(5, TimeUnit.SECONDS));

    }
}
