/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Set of methods for digest operations but with Strings.
 */
public final class Digest {
    // Every implementation of the Java platform is required to support SHA-256.
    public static final String SHA_256 = "SHA-256";

    private Digest() {
    }

    /**
     * Calculate digest for a UTF_8 encoded string input.
     * @param utfInput String to calculate digest for
     * @return the base64 encoded digest value for the string
     * @throws NoSuchAlgorithmException when no implementation for message digest is available
     * @throws IllegalArgumentException if input is invalid
     */
    public static String calculate(String utfInput) throws NoSuchAlgorithmException {
        if (Utils.isEmpty(utfInput)) {
            throw new IllegalArgumentException("Input is blank for calculating digest");
        }
        MessageDigest messageDigest = MessageDigest.getInstance(SHA_256);
        return Base64.getEncoder().encodeToString(messageDigest.digest(utfInput.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Calculate digest for a UTF_8 encoded string input.
     * @param utfInput String to calculate digest for
     * @return the base64 encoded digest value for the string
     * @throws NoSuchAlgorithmException when no implementation for message digest is available
     * @throws IllegalArgumentException if input is invalid
     */
    public static String calculateWithUrlEncoderNoPadding(String utfInput) throws NoSuchAlgorithmException {
        if (Utils.isEmpty(utfInput)) {
            throw new IllegalArgumentException("Input is blank for calculating digest");
        }
        MessageDigest messageDigest = MessageDigest.getInstance(SHA_256);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(messageDigest.digest(utfInput.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Compare two utf8 encoded digest strings.
     * @param digest1 first digest to compare
     * @param digest2 second digest to compare
     * @return whether two digests are equal
     */
    public static boolean isEqual(String digest1, String digest2) {
        return MessageDigest.isEqual(digest1.getBytes(StandardCharsets.UTF_8),
                digest2.getBytes(StandardCharsets.UTF_8));
    }
}
