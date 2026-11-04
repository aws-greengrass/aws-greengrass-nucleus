/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static com.aws.greengrass.componentmanager.plugins.docker.Registry.Credentials.EXPIRY_BUFFER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({GGExtension.class})
class RegistryTest {

    @Test
    void GIVEN_credentials_expiring_after_buffer_WHEN_check_validity_THEN_valid() {
        Registry.Credentials credentials =
                new Registry.Credentials("username", "password", Instant.now().plus(EXPIRY_BUFFER).plusSeconds(60));
        assertTrue(credentials.isValid());
    }

    @Test
    void GIVEN_credentials_expiring_within_buffer_WHEN_check_validity_THEN_invalid() {
        Registry.Credentials credentials =
                new Registry.Credentials("username", "password", Instant.now().plus(EXPIRY_BUFFER).minusSeconds(60));
        assertFalse(credentials.isValid());
    }

    @Test
    void GIVEN_expired_credentials_WHEN_check_validity_THEN_invalid() {
        Registry.Credentials credentials =
                new Registry.Credentials("username", "password", Instant.now().minusSeconds(60));
        assertFalse(credentials.isValid());
    }

    @Test
    void GIVEN_credentials_without_expiry_WHEN_check_validity_THEN_valid() {
        assertTrue(new Registry.Credentials("username", "password").isValid());
    }
}
