/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
class DigestTest {

    @Test
    void GIVEN_digest_WHEN_calculate_THEN_correct_value_returned() throws NoSuchAlgorithmException {
        assertThrows(IllegalArgumentException.class, () -> Digest.calculate(null));
        assertThrows(IllegalArgumentException.class, () -> Digest.calculate(""));
        assertThrows(IllegalArgumentException.class, () -> Digest.calculate(" "));

        String input1 = "Input1";
        String input2 = "Input2";
        String input3 = "Input2";

        MessageDigest md = MessageDigest.getInstance(Digest.SHA_256);
        assertEquals(Base64.getEncoder().encodeToString(md.digest(input1.getBytes(StandardCharsets.UTF_8))),
                Digest.calculate(input1));
        assertEquals(Base64.getEncoder().encodeToString(md.digest(input2.getBytes(StandardCharsets.UTF_8))),
                Digest.calculate(input2));

        String digest1 = Digest.calculate(input1);
        String digest2 = Digest.calculate(input2);
        String digest3 = Digest.calculate(input3);

        assertFalse(Digest.isEqual(digest1, digest2));
        assertTrue(Digest.isEqual(digest2, digest3));
    }
}
