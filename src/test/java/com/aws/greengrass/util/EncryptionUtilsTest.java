/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EncryptionUtilsTest {

    @TempDir
    protected static Path encryptionResourcePath;

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> certTypes() {
        return Stream.of(Arguments.of(2048, true, false), Arguments.of(2048, false, false),
                Arguments.of(256, true, true), Arguments.of(256, false, true));
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> keyTypes() {
        return Stream.of(Arguments.of(2048, true, false), Arguments.of(2048, false, false),
                Arguments.of(256, true, true), Arguments.of(256, false, true), Arguments.of(512, true, false),
                Arguments.of(1024, true, false), Arguments.of(4096, true, false));
    }

    @ParameterizedTest
    @MethodSource("certTypes")
    void GIVEN_certificate_WHEN_load_x509_certificates_THEN_succeed(int keySize, boolean pem, boolean ec)
            throws Exception {
        Path certificatePath =
                generateCertificateFile(keySize, pem, encryptionResourcePath.resolve("certificate-" + keySize + ".pem"),
                        ec).getLeft();

        List<X509Certificate> certificateList = EncryptionUtils.loadX509Certificates(certificatePath);

        assertThat(certificateList.size(), is(1));
        assertThat(certificateList.get(0), notNullValue());
        assertThat(certificateList.get(0).getType(), is("X.509"));
    }

    @ParameterizedTest
    @MethodSource("keyTypes")
    void GIVEN_pkcs8_WHEN_load_private_key_THEN_succeed(int keySize, boolean pem, boolean ec) throws Exception {
        Path privateKeyPath =
                generatePkCS8PrivateKeyFile(keySize, pem, encryptionResourcePath.resolve("pkcs8-" + keySize + ".pem"),
                        ec);

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is(ec ? "EC" : "RSA"));
    }

    @ParameterizedTest
    @MethodSource("keyTypes")
    void GIVEN_pkcs1_WHEN_load_private_key_THEN_succeed(int keySize, boolean pem, boolean ec) throws Exception {
        if (ec || !pem) {
            return;
        }
        Path privateKeyPath =
                generatePkCS1PrivateKeyFile(keySize, encryptionResourcePath.resolve("pkcs1-" + keySize + ".pem"));

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_key_invalid_WHEN_load_private_key_THEN_throw_exception() throws Exception {
        Path privateKeyPath = encryptionResourcePath.resolve("invalid-key.pem");
        writePemFile("RSA PRIVATE KEY", "this is private key".getBytes(), privateKeyPath);

        assertThrows(GeneralSecurityException.class, () -> EncryptionUtils.loadPrivateKey(privateKeyPath));
    }

    @Test
    void GIVEN_key_file_not_exist_WHEN_load_private_key_THEN_throw_exception() {
        Path privateKeyPath = encryptionResourcePath.resolve("some-key.pem");

        assertThrows(IOException.class, () -> EncryptionUtils.loadPrivateKey(privateKeyPath));
    }

    @Test
    void GIVEN_encoded_key_WHEN_convert_to_pem_THEN_return_same_pem_as_bouncycastle() throws Exception {
        KeyPair keyPair = generateRSAKeyPair(4096);
        String pem = EncryptionUtils.encodeToPem("PUBLIC KEY", keyPair.getPublic().getEncoded());

        //Generate pem using bouncycastle
        PemObject pemObject = new PemObject("PUBLIC KEY", keyPair.getPublic().getEncoded());
        try (StringWriter str = new StringWriter();
             JcaPEMWriter pemWriter = new JcaPEMWriter(str)) {
            pemWriter.writeObject(pemObject);
            pemWriter.close();
            assertEquals(str.toString(),pem);
        }
    }

    public static Pair<Path, KeyPair> generateCertificateFile(int keySize, boolean pem,
    Path filepath, boolean ec) throws Exception {
        KeyPair keyPair;
        if (ec) {
            keyPair = generateECKeyPair(keySize);
        } else {
            keyPair = generateRSAKeyPair(keySize);
        }
        X500Name name = new X500Name("CN=ROOT");
        SubjectPublicKeyInfo subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
        Date start = new Date();
        Date until = Date.from(LocalDate.now().plus(365, ChronoUnit.DAYS).atStartOfDay().toInstant(ZoneOffset.UTC));
        X509v3CertificateBuilder builder =
                new X509v3CertificateBuilder(name, new BigInteger(10, new SecureRandom()), start, until, name,
                        subjectPublicKeyInfo);
        String signingAlgo = "SHA256WithRSA";
        if (ec) {
            signingAlgo = "SHA256WITHECDSA";
        }
        ContentSigner signer = new JcaContentSignerBuilder(signingAlgo).setProvider(new BouncyCastleProvider())
                .build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate =
                new JcaX509CertificateConverter().setProvider(new BouncyCastleProvider()).getCertificate(holder);

        if (pem) {
            try (PrintWriter out = new PrintWriter(filepath.toFile())) {
                out.println("-----BEGIN CERTIFICATE-----");
                out.println(new String(Base64.encodeBase64(certificate.getEncoded())));
                out.println("-----END CERTIFICATE-----");
            }
        } else {
            try (OutputStream outputStream = Files.newOutputStream(filepath)) {
                outputStream.write(certificate.getEncoded());
            }
        }

        return new Pair<>(filepath, keyPair);
    }

    private static KeyPair generateRSAKeyPair(int keySize) throws Exception {
        KeyPairGenerator keygen = KeyPairGenerator.getInstance("RSA");
        keygen.initialize(keySize);
        return keygen.generateKeyPair();
    }

    private static KeyPair generateECKeyPair(int keySize) throws Exception {
        KeyPairGenerator keygen = KeyPairGenerator.getInstance("EC");
        keygen.initialize(keySize);
        return keygen.generateKeyPair();
    }

    public static Path generatePkCS8PrivateKeyFile(int keySize, boolean pem, Path filepath, boolean ecKey)
            throws Exception {
        KeyPair pair;
        if (ecKey) {
            pair = generateECKeyPair(keySize);
        } else {
            pair = generateRSAKeyPair(keySize);
        }
        byte[] privateKey = pair.getPrivate().getEncoded();

        if (pem && !ecKey) {
            writePemFile("PRIVATE KEY", privateKey, filepath);
        } else if (pem) {
            writePemFile("EC PRIVATE KEY", privateKey, filepath);
        } else {
            try (OutputStream outputStream = Files.newOutputStream(filepath)) {
                outputStream.write(privateKey);
            }
        }

        return filepath;
    }

    public static Path generatePkCS1PrivateKeyFile(int keySize, Path filepath) throws Exception {
        KeyPair pair = generateRSAKeyPair(keySize);
        byte[] privateKey = pair.getPrivate().getEncoded();
        PrivateKeyInfo keyInfo = PrivateKeyInfo.getInstance(privateKey);
        ASN1Encodable encodable = keyInfo.parsePrivateKey();
        ASN1Primitive primitive = encodable.toASN1Primitive();
        privateKey = primitive.getEncoded();

        writePemFile("RSA PRIVATE KEY", privateKey, filepath);

        return filepath;
    }

    public static void writePemFile(String type, byte[] content, Path filepath) throws Exception {
        PemObject pemObject = new PemObject(type, content);
        try (PrintWriter printWriter = new PrintWriter(filepath.toFile());
             PemWriter pemWriter = new PemWriter(printWriter)) {
            pemWriter.writeObject(pemObject);
        }
    }


}
