/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

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
        // We can't use Java internal APIs to parse ASN.1 structures, so we build a PKCS#8 key Java can understand
        int pkcs1Length = pkcs1Bytes.length;
        int totalLength = pkcs1Length + 22;
        // reference to https://github.com/Mastercard/client-encryption-java/blob/master/src/main/java/com/mastercard/developer/utils/EncryptionUtils.java#L95-L100
        // this method can save us from importing BouncyCastle as dependency
        byte[] pkcs8Header = {0x30, (byte) 0x82, (byte) ((totalLength >> 8) & 0xff), (byte) (totalLength & 0xff),
                // Sequence + total length
                0x2, 0x1, 0x0, // Integer (0)
                0x30, 0xD, 0x6, 0x9, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0xD, 0x1, 0x1, 0x1, 0x5, 0x0,
                // Sequence: 1.2.840.113549.1.1.1, NULL
                0x4, (byte) 0x82, (byte) ((pkcs1Length >> 8) & 0xff), (byte) (pkcs1Length & 0xff)
                // Octet string + length
        };
        byte[] pkcs8bytes = join(pkcs8Header, pkcs1Bytes);
        return readPkcs8PrivateKey(pkcs8bytes);
    }

    private static byte[] join(byte[] byteArray1, byte[] byteArray2) {
        byte[] bytes = new byte[byteArray1.length + byteArray2.length];
        System.arraycopy(byteArray1, 0, bytes, 0, byteArray1.length);
        System.arraycopy(byteArray2, 0, bytes, byteArray1.length, byteArray2.length);
        return bytes;
    }

    /**
     * Converts given encoded object to a PEM string.
     *
     * @param encodedObject   encoded entity
     * @param pemBoundaryType encoding boundary of pem
     * @return a PEM string
     * @throws IOException IOException
     */
    public static String encodeToPem(String pemBoundaryType, byte[] encodedObject) throws IOException {
        try (StringWriter str = new StringWriter();
             PemWriter pemWriter = new PemWriter(str)) {
            pemWriter.writeObject(pemBoundaryType, encodedObject);
            pemWriter.close(); // Need to explicitly close this as it is a buffered writer
            return str.toString();
        }
    }

    /**
     * Copyright (c) 2000 - 2021 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)
     * SPDX-License-Identifier: MIT
     *
     * <p>A generic PEM writer, based on RFC 1421
     * From: https://javadoc.io/static/org.bouncycastle/bcprov-jdk15on/1.62/org/bouncycastle/util/io/pem/PemWriter.html</p>
     */
    public static class PemWriter extends BufferedWriter {
        private static final int LINE_LENGTH = 64;
        private final char[] buf = new char[LINE_LENGTH];

        /**
         * Base constructor.
         *
         * @param out output stream to use.
         */
        public PemWriter(Writer out) {
            super(out);
        }

        /**
         * Writes a pem encoded string.
         *
         * @param type  key type.
         * @param bytes encoded string
         * @throws IOException IO Exception
         */
        public void writeObject(String type, byte[] bytes)
                throws IOException {
            writePreEncapsulationBoundary(type);
            writeEncoded(bytes);
            writePostEncapsulationBoundary(type);
        }

        private void writeEncoded(byte[] bytes)
                throws IOException {
            bytes = Base64.getEncoder().encode(bytes);

            for (int i = 0; i < bytes.length; i += buf.length) {
                int index = 0;

                while (index != buf.length) {
                    if ((i + index) >= bytes.length) {
                        break;
                    }
                    buf[index] = (char) bytes[i + index];
                    index++;
                }
                this.write(buf, 0, index);
                this.newLine();
            }
        }

        private void writePreEncapsulationBoundary(
                String type)
                throws IOException {
            this.write("-----BEGIN " + type + "-----");
            this.newLine();
        }

        private void writePostEncapsulationBoundary(
                String type)
                throws IOException {
            this.write("-----END " + type + "-----");
            this.newLine();
        }
    }
}
