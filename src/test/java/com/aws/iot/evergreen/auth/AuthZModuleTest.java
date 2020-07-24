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
public class AuthZModuleTest {

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
    void Given_authZmodule_WHEN_added_empty_entries_THEN_it_fails(String destination,
                                                                  String principle,
                                                                  String op,
                                                                  String resource) {
        AuthZModule module = new AuthZModule();
        Permission permission = Permission.builder().principle(principle).operation(op).resource(resource).build();
        assertThrows(AuthorizationException.class, () -> module.addPermission(destination, permission));
    }

    @ParameterizedTest
    @MethodSource("permissionEntries")
    void Given_authZmodule_WHEN_added_entries_THEN_retrieve_works(String destination,
                                                                  String principle,
                                                                  String op,
                                                                  String resource) throws AuthorizationException {
        AuthZModule module = new AuthZModule();
        Permission permission = Permission.builder().principle(principle).operation(op).resource(resource).build();
        module.addPermission(destination, permission);
        assertTrue(module.isPresent(destination, permission));
    }

    @Test
    void Given_authZmodule_WHEN_added_entries_successively_THEN_retrieve_works() {
        AuthZModule module = new AuthZModule();
        permissionEntries().forEach(entry -> {
            String destination = (String)entry.get()[0];
            String principle = (String)entry.get()[1];
            String op = (String)entry.get()[2];
            String resource = (String)entry.get()[3];
            try {
                Permission permission = Permission.builder().principle(principle).operation(op).resource(resource).build();
                module.addPermission(destination, permission);
                assertTrue(module.isPresent(destination, permission));
            } catch (AuthorizationException e) {
                fail("Encountered exception ", e);
            }
        });
    }
}
