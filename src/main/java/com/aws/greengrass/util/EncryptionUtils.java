/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;


public final class EncryptionUtils {

    public static final String CERTIFICATE_PEM_HEADER = "-----BEGIN CERTIFICATE-----";
    public static final String CERTIFICATE_PEM_FOOTER = "-----END CERTIFICATE-----";

    private static final String PKCS_1_PEM_HEADER = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String PKCS_1_PEM_FOOTER = "-----END RSA PRIVATE KEY-----";
    private static final String PKCS_8_PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS_8_PEM_FOOTER = "-----END PRIVATE KEY-----";
    private static final String PKCS_8_EC_HEADER = "-----BEGIN EC PRIVATE KEY-----";
    private static final String PKCS_8_EC_FOOTER = "-----END EC PRIVATE KEY-----";
    private static final String EC_TYPE = "EC";
    private static final String RSA_TYPE = "RSA";

    private EncryptionUtils() {
    }

    /**
     * Populate a list of X509 encryption certificate objects from the given file path.
     *
     * @param certificatePath certificate file path
     * @return a list of X590 certificate objects
     * @throws IOException          file IO error
     * @throws CertificateException can't populate certificates
     */
    public static List<X509Certificate> loadX509Certificates(Path certificatePath)
            throws IOException, CertificateException {
        try (InputStream certificateInputStream = Files.newInputStream(certificatePath)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return new ArrayList<>(
                    (Collection<? extends X509Certificate>) factory.generateCertificates(certificateInputStream));
        }
    }

    public static PrivateKey loadPrivateKey(Path keyPath) throws IOException, GeneralSecurityException {
        return loadPrivateKeyPair(keyPath).getPrivate();
    }

    /**
     * Load an RSA keypair from the given file path.
     *
     * @param keyPath key file path
     * @return an RSA keypair
     * @throws IOException              file IO error
     * @throws GeneralSecurityException can't load private key
     */
    public static KeyPair loadPrivateKeyPair(Path keyPath) throws IOException, GeneralSecurityException {
        byte[] keyBytes = Files.readAllBytes(keyPath);
        String keyString = new String(keyBytes, StandardCharsets.UTF_8);

        keyString = keyString.replace("\\n", "");
        keyString = keyString.replace("\\r", "");

        if (keyString.contains(PKCS_1_PEM_HEADER)) {
            keyString = keyString.replace(PKCS_1_PEM_HEADER, "");
            keyString = keyString.replace(PKCS_1_PEM_FOOTER, "");
            return readPkcs1PrivateKey(Base64.getMimeDecoder().decode(keyString));
        }

        if (keyString.contains(PKCS_8_PEM_HEADER)) {
            keyString = keyString.replace(PKCS_8_PEM_HEADER, "");
            keyString = keyString.replace(PKCS_8_PEM_FOOTER, "");
            return readPkcs8PrivateKey(Base64.getMimeDecoder().decode(keyString));
        }

        if (keyString.contains(PKCS_8_EC_HEADER)) {
            keyString = keyString.replace(PKCS_8_EC_HEADER, "");
            keyString = keyString.replace(PKCS_8_EC_FOOTER, "");
            return readPkcs8PrivateKey(Base64.getMimeDecoder().decode(keyString));
        }

        return readPkcs8PrivateKey(keyBytes);
    }

    private static KeyPair readPkcs8PrivateKey(byte[] pkcs8Bytes) throws GeneralSecurityException {
        InvalidKeySpecException exception;
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_TYPE);
            KeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(keySpec);
            RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(privateKey.getModulus(),
                    privateKey.getPublicExponent());
            return new KeyPair(keyFactory.generatePublic(publicKeySpec), privateKey);
        } catch (InvalidKeySpecException e) {
            exception = e;
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(EC_TYPE);
            KeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);
            ECPrivateKey privateKey = (ECPrivateKey) keyFactory.generatePrivate(keySpec);
            ECPublicKeySpec publicKeySpec = new ECPublicKeySpec(privateKey.getParams().getGenerator(),
                    privateKey.getParams());
            return new KeyPair(keyFactory.generatePublic(publicKeySpec), privateKey);
        } catch (InvalidKeySpecException e) {
            exception.addSuppressed(e);
            throw exception;
        }
    }

    private static KeyPair readPkcs1PrivateKey(byte[] pkcs1Bytes) throws GeneralSecurityException {
        try {
            ASN1Sequence seq = ASN1Sequence.getInstance(pkcs1Bytes);
            if (seq.size() != 9) {
                throw new InvalidKeySpecException("Invalid RSA Private Key ASN1 sequence.");
            }
            RSAPrivateKey key = RSAPrivateKey.getInstance(seq);
            RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent());
            RSAPrivateCrtKeySpec privSpec = new RSAPrivateCrtKeySpec(
                    key.getModulus(),
                    key.getPublicExponent(),
                    key.getPrivateExponent(),
                    key.getPrime1(),
                    key.getPrime2(),
                    key.getExponent1(),
                    key.getExponent2(),
                    key.getCoefficient()
            );
            KeyFactory fact = KeyFactory.getInstance(RSA_TYPE);
            PublicKey publicKey = fact.generatePublic(pubSpec);
            PrivateKey privateKey = fact.generatePrivate(privSpec);
            return new KeyPair(publicKey, privateKey);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Failed to get keypair", e);
        }
    }
}
