/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth;

import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(EGExtension.class)
public class AuthorizationModuleTest {

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
}
