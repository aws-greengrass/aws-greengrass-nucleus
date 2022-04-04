/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(GGExtension.class)
class AuthorizationModuleTest {

    private static Stream<Arguments> permissionEntries() {
        return Stream.of(
                Arguments.of("ComponentA", "ComponentB", "Op1", "res1"),
                Arguments.of("ComponentA", "ComponentB", "Op1", "res2"),
                Arguments.of("ComponentA", "ComponentB", "Op2", "res2"),
                Arguments.of("ComponentA", "ComponentC", "Op2", "res2"),
                Arguments.of("ComponentB", "ComponentC", "Op2", "res2"),
                Arguments.of("ComponentB", "ComponentC", "Op2", "res2"),
                Arguments.of("ComponentB", "ComponentC", "Op2", "res2"));
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> invalidPermEntries() {
        return Stream.of(
                Arguments.of(null, "ComponentB", "Op1", "res1"),
                Arguments.of("", "ComponentC", "Op2", "res2"),
                Arguments.of("ComponentB", "", "Op2", "res2"),
                Arguments.of("ComponentB", "ComponentC", "", "res2"),
                Arguments.of("ComponentB", "ComponentC", "op2", ""),
                Arguments.of(" ", "ComponentC", "Op2", "res2"),
                Arguments.of("ComponentB", " ", "Op2", "res2"),
                Arguments.of("ComponentB", "ComponentC", " ", "res2"),
                Arguments.of("ComponentB", "ComponentC", "op2", " "));
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> invalidResources1() {
        return Stream.of(
                Arguments.of("res${?}res?entry"),
                Arguments.of("res?entry"),
                Arguments.of("res${*}?"),
                Arguments.of("?res${*}"),
                Arguments.of("res${$}{?}")
                );
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> invalidResources2() {
        return Stream.of(
                Arguments.of("res${?}abc${123}"),
                Arguments.of("res${*}abc${}"),
                Arguments.of("res${*}abc${$*}"),
                Arguments.of("res${xyz"),
                Arguments.of("res${asd${*}abc")
        );
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> invalidResources3() {
        return Stream.of(
                Arguments.of("res${?}abc${x}"),
                Arguments.of("res${*}abc${/}"),
                Arguments.of("abc${.}res${$}")
        );
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> validResources() {
        return Stream.of(
                Arguments.of("res${*}${?}${$}", "res*?$"),
                Arguments.of("res*${?}${*}${$}$", "res*?*$$"),
                Arguments.of("res*$${*}${$}", "res*$*$"),
                Arguments.of("${?}${*}$$}${$}", "?*$$}$"),
                Arguments.of("abc$", "abc$"),
                Arguments.of("*", "*")
                );
    }

    @ParameterizedTest
    @MethodSource("invalidPermEntries")
    void Given_authZmodule_WHEN_added_invalid_entries_THEN_it_fails(String destination,
                                                                  String principal,
                                                                  String op,
                                                                  String resource) {
        AuthorizationModule module = new AuthorizationModule();
        Permission permission = Permission.builder().principal(principal).operation(op).resource(resource).build();
        assertThrows(AuthorizationException.class, () -> module.addPermission(destination, permission));
    }

    @ParameterizedTest
    @MethodSource("invalidResources1")
    void Given_authZmodule_WHEN_added_resources_with_unescaped_questionmark_characterTHEN_parseResource_fails(String res) {
        AuthorizationModule module = new AuthorizationModule();
        // test resources containing unescaped character '?'
        Permission permission = Permission.builder().principal("principal").operation("op").resource(res).build();
        AuthorizationException authorizationException = assertThrows(AuthorizationException.class, () -> module.addPermission("destination", permission));
        assertEquals("Resource can not contain character '?' without escaping", authorizationException.getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidResources2")
    void Given_authZmodule_WHEN_added_resources_with_invalid_escape_sequence_THEN_parseResource_fails(String res) {
        AuthorizationModule module = new AuthorizationModule();
        // test resources containing invalid escape sequence
        Permission permission = Permission.builder().principal("principal").operation("op").resource(res).build();
        AuthorizationException authorizationException = assertThrows(AuthorizationException.class, () -> module.addPermission("destination", permission));
        assertEquals("Resource can not contain invalid escape sequence", authorizationException.getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidResources3")
    void Given_authZmodule_WHEN_added_resources_with_invalid_escaped_chars_THEN_parseResource_fails(String res) {
        AuthorizationModule module = new AuthorizationModule();
        // test resources escaping normal characters (anything other than $, ?, *)
        Permission permission = Permission.builder().principal("principal").operation("op").resource(res).build();
        AuthorizationException authorizationException = assertThrows(AuthorizationException.class, () -> module.addPermission("destination", permission));
        assertEquals("Resource can not have escaping for normal characters, only special characters ('*', '$', '?') can be escaped", authorizationException.getMessage());
    }

    @ParameterizedTest
    @MethodSource("validResources")
    void Given_authZmodule_WHEN_added_valid_resources_THEN_parseResource_works(String res, String expectedRes) throws AuthorizationException {
        AuthorizationModule module = new AuthorizationModule();
        Permission permission = Permission.builder().principal("principal").operation("op").resource(res).build();
        module.addPermission("destination", permission);
        assertThat(module.getResources("destination", "principal", "op"), containsInAnyOrder(expectedRes));
    }

    @ParameterizedTest
    @MethodSource("invalidPermEntries")
    void Given_authZmodule_WHEN_checked_with_invalid_entries_THEN_it_fails(String destination,
                                                                  String principal,
                                                                  String op,
                                                                  String resource) {
        AuthorizationModule module = new AuthorizationModule();
        Permission permission = Permission.builder().principal(principal).operation(op).resource(resource).build();
        assertThrows(AuthorizationException.class, () -> module.isPresent(destination, permission));
    }

    @ParameterizedTest
    @MethodSource("permissionEntries")
    void Given_authZmodule_WHEN_added_entries_THEN_retrieve_works(String destination,
                                                                  String principal,
                                                                  String op,
                                                                  String resource) throws AuthorizationException {
        AuthorizationModule module = new AuthorizationModule();
        Permission permission = Permission.builder().principal(principal).operation(op).resource(resource).build();
        module.addPermission(destination, permission);
        assertTrue(module.isPresent(destination, permission));
    }

    @Test
    void Given_authZmodule_WHEN_given_component_and_clear_permissions_THEN_delete_permissions() {
        AuthorizationModule module = new AuthorizationModule();
        permissionEntries().forEach(entry -> {
            String destination = (String)entry.get()[0];
            String principal = (String)entry.get()[1];
            String op = (String)entry.get()[2];
            String resource = (String)entry.get()[3];
            try {
                Permission permission = Permission.builder().principal(principal).operation(op).resource(resource).build();
                module.addPermission(destination, permission);
                assertTrue(module.isPresent(destination, permission));
            } catch (AuthorizationException e) {
                fail("Encountered exception ", e);
            }
        });
        String componentToRemove = "ComponentB";
        module.deletePermissionsWithDestination(componentToRemove);
        assertThat(module.resourceAuthZCompleteMap, not(hasKey("ComponentB")));
    }

    @Test
    void Given_authZmodule_WHEN_added_entries_successively_THEN_retrieve_works() {
        AuthorizationModule module = new AuthorizationModule();
        permissionEntries().forEach(entry -> {
            String destination = (String)entry.get()[0];
            String principal = (String)entry.get()[1];
            String op = (String)entry.get()[2];
            String resource = (String)entry.get()[3];
            try {
                Permission permission = Permission.builder().principal(principal).operation(op).resource(resource).build();
                module.addPermission(destination, permission);
                assertTrue(module.isPresent(destination, permission));
            } catch (AuthorizationException e) {
                fail("Encountered exception ", e);
            }
        });
    }

    @Test
    void Given_authZmodule_WHEN_added_entries_THEN_getResources_works() throws AuthorizationException {
        AuthorizationModule module = new AuthorizationModule();
        String[][] combinations = {
                {"ServiceA", "compA", "opA", "res1"},
                {"ServiceA", "compA", "opA", "res2"},
                {"ServiceA", "*", "opA", "res1"},
                {"ServiceA", "*", "opA", "res2"},
                {"ServiceA", "compA", "*", "res2"},
                {"ServiceA", "compA", "*", "res3"},
                {"ServiceB", "compA", "opA", "res1"},
                {"ServiceB", "compA", "opA", "res2"},
        };

        for (String[] combination : combinations) {
            String destination = combination[0];
            String principal = combination[1];
            String op = combination[2];
            String resource = combination[3];
            Permission permission = Permission.builder().principal(principal).operation(op).resource(resource).build();
            module.addPermission(destination, permission);
            assertTrue(module.isPresent(destination, permission));
        }

        Set<String> allowedResources = module.getResources("ServiceA", "compA", "opA");
        assertThat(allowedResources, containsInAnyOrder("res1", "res2", "res3"));

        allowedResources = module.getResources("ServiceA", "compA", "opB");
        assertThat(allowedResources, containsInAnyOrder("res2", "res3"));

        allowedResources = module.getResources("ServiceA", "compB", "opA");
        assertThat(allowedResources, containsInAnyOrder("res1", "res2"));

        allowedResources = module.getResources("ServiceA", "compB", "opB");
        assertThat(allowedResources, is(empty()));

        allowedResources = module.getResources("ServiceB", "compA", "opA");
        assertThat(allowedResources, containsInAnyOrder("res1", "res2"));

        assertThrows(AuthorizationException.class, () -> module.getResources("ServiceA", "compA", "*"));
        assertThrows(AuthorizationException.class, () -> module.getResources("ServiceA", "*", "opA"));
    }
}
