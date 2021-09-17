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
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EncryptionUtilsTest {

    @TempDir
    protected static Path encryptionResourcePath;

    @Test
    void GIVEN_2048_certificate_pem_WHEN_load_x509_certificates_THEN_succeed() throws Exception {
        Path certificatePath = generateCertificateFile(2048, true,
                encryptionResourcePath.resolve("certificate-2048.pem"));

        List<X509Certificate> certificateList = EncryptionUtils.loadX509Certificates(certificatePath);

        assertThat(certificateList.size(), is(1));
        assertThat(certificateList.get(0), notNullValue());
        assertThat(certificateList.get(0).getType(), is("X.509"));
    }

    @Test
    void GIVEN_2048_certificate_der_WHEN_load_x509_certificates_THEN_succeed() throws Exception {
        Path certificatePath = generateCertificateFile(2048, false,
                encryptionResourcePath.resolve("certificate-2048.der"));

        List<X509Certificate> certificateList = EncryptionUtils.loadX509Certificates(certificatePath);

        assertThat(certificateList.size(), is(1));
        assertThat(certificateList.get(0), notNullValue());
        assertThat(certificateList.get(0).getType(), is("X.509"));
    }

    @Test
    void GIVEN_2048_pkcs8_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        Path privateKeyPath = generatePkCS8PrivateKeyFile(2048, true,
                encryptionResourcePath.resolve("pkcs8-2048.pem"));

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_2048_pkcs8_der_WHEN_load_private_key_THEN_succeed() throws Exception {
        Path privateKeyPath = generatePkCS8PrivateKeyFile(2048, false,
                encryptionResourcePath.resolve("pkcs8-2048.der"));

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_2048_pkcs1_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        Path privateKeyPath = generatePkCS1PrivateKeyFile(2048,
                encryptionResourcePath.resolve("pkcs1-2048.pem"));

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_1024_pkcs1_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        Path privateKeyPath = generatePkCS1PrivateKeyFile(1024,
                encryptionResourcePath.resolve("pkcs1-1024.pem"));

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_4096_pkcs1_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        Path privateKeyPath = generatePkCS1PrivateKeyFile(4096,
                encryptionResourcePath.resolve("pkcs1-4096.pem"));

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_512_pkcs1_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        Path privateKeyPath = generatePkCS1PrivateKeyFile(512,
                encryptionResourcePath.resolve("pkcs1-512.pem"));

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

    public static Path generateCertificateFile(int keySize, boolean pem, Path filepath) throws Exception {
        KeyPair keyPair = generateRSAKeyPair(keySize);
        X500Name name = new X500Name("CN=ROOT");
        SubjectPublicKeyInfo subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
        Date start = new Date();
        Date until = Date.from(LocalDate.now().plus(365, ChronoUnit.DAYS).atStartOfDay().toInstant(ZoneOffset.UTC));
        X509v3CertificateBuilder builder =
                new X509v3CertificateBuilder(name, new BigInteger(10, new SecureRandom()), start, until, name,
                        subjectPublicKeyInfo);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").setProvider(new BouncyCastleProvider())
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

        return filepath;
    }

    private static KeyPair generateRSAKeyPair(int keySize) throws Exception {
        KeyPairGenerator keygen = KeyPairGenerator.getInstance("RSA");
        keygen.initialize(keySize);
        return keygen.generateKeyPair();
    }

    public static Path generatePkCS8PrivateKeyFile(int keySize, boolean pem, Path filepath) throws Exception {
        KeyPair pair = generateRSAKeyPair(keySize);
        byte[] privateKey = pair.getPrivate().getEncoded();

        if (pem) {
            writePemFile("PRIVATE KEY", privateKey, filepath);
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
