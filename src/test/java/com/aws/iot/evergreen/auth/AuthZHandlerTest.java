package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthZException;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class AuthZHandlerTest {

    @Mock
    private Kernel mockKernel;

    @Mock
    private Topics mockTopics;

    private AuthZPolicy getAuthZPolicy() {
        return AuthZPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .sources(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }

    private AuthZPolicy getAuthZPolicyB() {
        return AuthZPolicy.builder()
                .policyId("Id2")
                .policyDescription("Test policy")
                .sources(new HashSet(Arrays.asList("ServiceC", "ServiceD")))
                .operations(new HashSet(Arrays.asList("OpD", "OpE")))
                .build();
    }

    private AuthZPolicy getStarOperationsAuthZPolicy() {
        return AuthZPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .sources(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("*")))
                .build();
    }

    private AuthZPolicy getStarResourceAuthZPolicy() {
        return AuthZPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .sources(new HashSet(Arrays.asList("compA")))
                .operations(new HashSet(Arrays.asList("OpA")))
                .resources(new HashSet(Arrays.asList("*")))
                .build();
    }

    private AuthZPolicy getStarSourcesAuthZPolicy() {
        return AuthZPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .sources(new HashSet(Arrays.asList("*")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }

    private AuthZPolicy getAuthZPolicyWithEmptySources() {
        return AuthZPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .sources(new HashSet())
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }

    private AuthZPolicy getAuthZPolicyWithEmptyOp() {
        return AuthZPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .sources(new HashSet(Arrays.asList("*")))
                .operations(new HashSet())
                .build();
    }

    @Test
    void GIVEN_AuthZ_manager_WHEN_service_registered_twice_THEN_errors() throws AuthZException {
        AuthZHandler authZHandler = new AuthZHandler(mockKernel);
        final Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authZHandler.registerService("ServiceA", serviceOps);

        assertThrows(AuthZException.class, () -> authZHandler.registerService("ServiceA", serviceOps));
        final Set<String> serviceOps_2 = new HashSet<>(Arrays.asList("OpA"));
        assertThrows(AuthZException.class, () -> authZHandler.registerService("ServiceA", serviceOps_2));

        // Another service can be registered
        authZHandler.registerService("ServiceB", serviceOps_2);
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceC",
                Permission.builder().source("*").operation("*").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_manager_WHEN_service_registered_THEN_auth_works() throws Exception {
        AuthZHandler authZHandler = new AuthZHandler(mockKernel);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authZHandler.registerService("ServiceA", serviceOps);

        Set<String> serviceOpsB = new HashSet<>(Arrays.asList("OpD", "OpE"));
        authZHandler.registerService("ServiceB", serviceOpsB);

        authZHandler.loadAuthZConfig("ServiceA", Collections.singletonList(getAuthZPolicy()));
        authZHandler.loadAuthZConfig("ServiceB", Collections.singletonList(getAuthZPolicyB()));

        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpA").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpB").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpC").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compB").operation("OpC").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compB").operation("OpB").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compB").operation("OpA").resource(null).build()));

        assertTrue(authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("ServiceD").operation("OpD").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("ServiceD").operation("OpE").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("ServiceC").operation("OpD").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("ServiceC").operation("OpE").resource(null).build()));

        // Services are not allowed to be accessed from other sources
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compC").operation("OpA").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compA").operation("OpD").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compA").operation("OpE").resource(null).build()));

        // Services are not allowed to be accessed for non allowed ops
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compB").operation("Op").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("Op").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("ServiceD").operation("Op").resource(null).build()));

        // services are not allowed to be accessed from * source
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("*").operation("OpA").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("random").operation("OpA").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("*").operation("OpD").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("random").operation("OpD").resource(null).build()));

        // services are not allowed to be accessed for * operation
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("*").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("ServiceD").operation("*").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_manager_WHEN_service_registered_THEN_auth_lookup_for_star_operation_works() throws Exception {
        AuthZHandler authZHandler = new AuthZHandler(mockKernel);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authZHandler.registerService("ServiceA", serviceOps);
        authZHandler.registerService("ServiceB", serviceOps);

        AuthZPolicy policy = getStarOperationsAuthZPolicy();
        authZHandler.loadAuthZConfig("ServiceA", Collections.singletonList(policy));
        // All registered Operations are allowed now
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpA").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpB").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpC").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compB").operation("OpC").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compB").operation("OpB").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compB").operation("OpA").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compB").operation("*").resource(null).build()));

        // random destination should not be allowed
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compC").operation("OpA").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compA").operation("OpA").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compA").operation("OpB").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compA").operation("OpC").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compB").operation("OpC").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compB").operation("OpB").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compB").operation("OpA").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_manager_WHEN_service_registered_THEN_auth_lookup_for_star_resource_works() throws Exception {
        AuthZHandler authZHandler = new AuthZHandler(mockKernel);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA"));
        authZHandler.registerService("ServiceA", serviceOps);

        AuthZPolicy policy = getStarResourceAuthZPolicy();
        authZHandler.loadAuthZConfig("ServiceA", Collections.singletonList(policy));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpA").resource("*").build()));

        // A random string works
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpA").resource("randomString").build()));

        // null resource be allowed as it will pass * check
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpA").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_manager_WHEN_service_registered_THEN_auth_lookup_for_star_source_works() throws Exception {
        AuthZHandler authZHandler = new AuthZHandler(mockKernel);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authZHandler.registerService("ServiceA", serviceOps);
        authZHandler.registerService("ServiceB", serviceOps);

        AuthZPolicy policy = getStarSourcesAuthZPolicy();
        authZHandler.loadAuthZConfig("ServiceA", Collections.singletonList(policy));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpA").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpB").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpC").resource(null).build()));
        // A non allowed Op is denied
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compA").operation("OpD").resource(null).build()));

        // Random Sources should be allowed now
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("*").operation("OpA").resource(null).build()));
        assertTrue(authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("random").operation("OpA").resource(null).build()));

        // Random destination should not be allowed
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceA",
                Permission.builder().source("compC").operation("*").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compA").operation("OpA").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compA").operation("OpB").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("*").operation("OpC").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compB").operation("OpC").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("compB").operation("OpB").resource(null).build()));
        assertThrows(AuthZException.class, () -> authZHandler.isFlowAuthorized("ServiceB",
                Permission.builder().source("*").operation("*").resource(null).build()));
    }

    @Test
    void GIVEN_authZ_handler_WHEN_loaded_incorrect_config_THEN_load_fails() throws Exception {
        AuthZHandler authZHandler = new AuthZHandler(mockKernel);

        // invalid service fails
        assertThrows(AuthZException.class, () -> authZHandler.loadAuthZConfig("",
                Collections.singletonList(getAuthZPolicy())));
        assertThrows(AuthZException.class, () -> authZHandler.loadAuthZConfig(null,
                Collections.singletonList(getAuthZPolicy())));
        // adding null config fails
        assertThrows(AuthZException.class, () -> authZHandler.loadAuthZConfig("ServiceA", null));
        assertThrows(AuthZException.class, () -> authZHandler.loadAuthZConfig("ServiceA", new ArrayList<>()));

        // When kernel cannot identify a source service then load fails
        assertThrows(AuthZException.class, () -> authZHandler.loadAuthZConfig("ServiceA",
                Collections.singletonList(getAuthZPolicy())));

        // Empty source should fail to load
        assertThrows(AuthZException.class, () -> authZHandler.loadAuthZConfig("ServiceA",
                Collections.singletonList(getAuthZPolicyWithEmptySources())));

        // Empty operations should fail to load
        assertThrows(AuthZException.class, () -> authZHandler.loadAuthZConfig("ServiceA",
                Collections.singletonList(getAuthZPolicyWithEmptyOp())));
    }
}
