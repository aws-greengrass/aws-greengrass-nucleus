package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class AuthorizationHandlerTest {

    @Mock
    private Kernel mockKernel;

    @Mock
    private Topics mockTopics;

    private AuthorizationPolicy getAuthZPolicy() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principles(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }

    private List<AuthorizationPolicy> getAuthZPolicyWithDuplicateId() {
        AuthorizationPolicy policy1 = AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principles(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
        AuthorizationPolicy policy2 = AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principles(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
        return Arrays.asList(policy1, policy2);
    }

    private AuthorizationPolicy getAuthZPolicyB() {
        return AuthorizationPolicy.builder()
                .policyId("Id2")
                .policyDescription("Test policy")
                .principles(new HashSet(Arrays.asList("ServiceC", "ServiceD")))
                .operations(new HashSet(Arrays.asList("OpD", "OpE")))
                .build();
    }

    private AuthorizationPolicy getStarOperationsAuthZPolicy() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principles(new HashSet(Arrays.asList("compA", "compB")))
                .operations(new HashSet(Arrays.asList("*")))
                .build();
    }

    private AuthorizationPolicy getStarResourceAuthZPolicy() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principles(new HashSet(Arrays.asList("compA")))
                .operations(new HashSet(Arrays.asList("OpA")))
                .resources(new HashSet(Arrays.asList("*")))
                .build();
    }

    private AuthorizationPolicy getStarSourcesAuthZPolicy() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principles(new HashSet(Arrays.asList("*")))
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }

    private AuthorizationPolicy getAuthZPolicyWithEmptySources() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principles(new HashSet())
                .operations(new HashSet(Arrays.asList("OpA", "OpB", "OpC")))
                .build();
    }

    private AuthorizationPolicy getAuthZPolicyWithEmptyOp() {
        return AuthorizationPolicy.builder()
                .policyId("Id1")
                .policyDescription("Test policy")
                .principles(new HashSet(Arrays.asList("*")))
                .operations(new HashSet())
                .build();
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_authz_policy_with_duplicate_id_THEN_load_fails() throws AuthorizationException {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel);
        final Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authorizationHandler.registerService("ServiceA", serviceOps);
        assertThrows(AuthorizationException.class,
                () ->authorizationHandler.loadAuthorizationPolicy("ServiceA", getAuthZPolicyWithDuplicateId()));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_service_registered_twice_THEN_errors() throws AuthorizationException {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel);
        final Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authorizationHandler.registerService("ServiceA", serviceOps);

        assertThrows(AuthorizationException.class, () -> authorizationHandler.registerService("ServiceA", serviceOps));
        final Set<String> serviceOps_2 = new HashSet<>(Arrays.asList("OpA"));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.registerService("ServiceA", serviceOps_2));

        // Another service can be registered
        authorizationHandler.registerService("ServiceB", serviceOps_2);
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceC",
                Permission.builder().principle("*").operation("*").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_service_registered_THEN_auth_works() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authorizationHandler.registerService("ServiceA", serviceOps);

        Set<String> serviceOpsB = new HashSet<>(Arrays.asList("OpD", "OpE"));
        authorizationHandler.registerService("ServiceB", serviceOpsB);

        authorizationHandler.loadAuthorizationPolicy("ServiceA", Collections.singletonList(getAuthZPolicy()));
        authorizationHandler.loadAuthorizationPolicy("ServiceB", Collections.singletonList(getAuthZPolicyB()));

        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpC").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compB").operation("OpC").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compB").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compB").operation("OpA").resource(null).build()));

        assertTrue(authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("ServiceD").operation("OpD").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("ServiceD").operation("OpE").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("ServiceC").operation("OpD").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("ServiceC").operation("OpE").resource(null).build()));

        // Services are not allowed to be accessed from other principles
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compC").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compA").operation("OpD").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compA").operation("OpE").resource(null).build()));

        // Services are not allowed to be accessed for non allowed ops
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compB").operation("Op").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("Op").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("ServiceD").operation("Op").resource(null).build()));

        // services are not allowed to be accessed from * principal
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("*").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("random").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("*").operation("OpD").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("random").operation("OpD").resource(null).build()));

        // services are not allowed to be accessed for * operation
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("*").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("ServiceD").operation("*").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_service_registered_THEN_auth_lookup_for_star_operation_works() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authorizationHandler.registerService("ServiceA", serviceOps);
        authorizationHandler.registerService("ServiceB", serviceOps);

        AuthorizationPolicy policy = getStarOperationsAuthZPolicy();
        authorizationHandler.loadAuthorizationPolicy("ServiceA", Collections.singletonList(policy));
        // All registered Operations are allowed now
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpC").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compB").operation("OpC").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compB").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compB").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compB").operation("*").resource(null).build()));

        // random destination should not be allowed
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compC").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compA").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compA").operation("OpB").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compA").operation("OpC").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compB").operation("OpC").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compB").operation("OpB").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compB").operation("OpA").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_service_registered_THEN_auth_lookup_for_star_resource_works() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel);
        when(mockKernel.findServiceTopic(anyString())).thenReturn(mockTopics);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA"));
        authorizationHandler.registerService("ServiceA", serviceOps);

        AuthorizationPolicy policy = getStarResourceAuthZPolicy();
        authorizationHandler.loadAuthorizationPolicy("ServiceA", Collections.singletonList(policy));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpA").resource("*").build()));

        // A random string works
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpA").resource("randomString").build()));

        // null resource be allowed as it will pass * check
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpA").resource(null).build()));
    }

    @Test
    void GIVEN_AuthZ_handler_WHEN_service_registered_THEN_auth_lookup_for_star_source_works() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel);
        Set<String> serviceOps = new HashSet<>(Arrays.asList("OpA", "OpB", "OpC"));
        authorizationHandler.registerService("ServiceA", serviceOps);
        authorizationHandler.registerService("ServiceB", serviceOps);

        AuthorizationPolicy policy = getStarSourcesAuthZPolicy();
        authorizationHandler.loadAuthorizationPolicy("ServiceA", Collections.singletonList(policy));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpB").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpC").resource(null).build()));
        // A non allowed Op is denied
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compA").operation("OpD").resource(null).build()));

        // Random Sources should be allowed now
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("*").operation("OpA").resource(null).build()));
        assertTrue(authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("random").operation("OpA").resource(null).build()));

        // Random destination should not be allowed
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceA",
                Permission.builder().principle("compC").operation("*").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compA").operation("OpA").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compA").operation("OpB").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("*").operation("OpC").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compB").operation("OpC").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("compB").operation("OpB").resource(null).build()));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.isAuthorized("ServiceB",
                Permission.builder().principle("*").operation("*").resource(null).build()));
    }

    @Test
    void GIVEN_authZ_handler_WHEN_loaded_incorrect_config_THEN_load_fails() throws Exception {
        AuthorizationHandler authorizationHandler = new AuthorizationHandler(mockKernel);

        // invalid service fails
        assertThrows(AuthorizationException.class, () -> authorizationHandler.loadAuthorizationPolicy("",
                Collections.singletonList(getAuthZPolicy())));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.loadAuthorizationPolicy(null,
                Collections.singletonList(getAuthZPolicy())));
        // adding null config fails
        assertThrows(AuthorizationException.class, () -> authorizationHandler.loadAuthorizationPolicy("ServiceA", null));
        assertThrows(AuthorizationException.class, () -> authorizationHandler.loadAuthorizationPolicy("ServiceA", new ArrayList<>()));

        // When kernel cannot identify a principal service then load fails
        assertThrows(AuthorizationException.class, () -> authorizationHandler.loadAuthorizationPolicy("ServiceA",
                Collections.singletonList(getAuthZPolicy())));

        // Empty principal should fail to load
        assertThrows(AuthorizationException.class, () -> authorizationHandler.loadAuthorizationPolicy("ServiceA",
                Collections.singletonList(getAuthZPolicyWithEmptySources())));

        // Empty operations should fail to load
        assertThrows(AuthorizationException.class, () -> authorizationHandler.loadAuthorizationPolicy("ServiceA",
                Collections.singletonList(getAuthZPolicyWithEmptyOp())));
    }
}
