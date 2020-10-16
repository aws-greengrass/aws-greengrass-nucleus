package com.aws.greengrass.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionUtilsTest {

    private static Path ENCRYPTION_RESOURCE_PATH;

    static {
        try {
            ENCRYPTION_RESOURCE_PATH = Paths.get(EncryptionUtilsTest.class.getResource("encryption").toURI());
        } catch (URISyntaxException ignore) {
        }
    }

    @Test
    void GIVEN_2048_certificate_pem_WHEN_load_x509_certificates_THEN_succeed() throws Exception {
        String certificatePath = ENCRYPTION_RESOURCE_PATH.resolve("certificate-2048.pem").toString();

        List<X509Certificate> certificateList = EncryptionUtils.loadX509Certificates(certificatePath);

        assertThat(certificateList.size(), is(1));
        assertThat(certificateList.get(0), notNullValue());
        assertThat(certificateList.get(0).getType(), is("X.509"));
    }

    @Test
    void GIVEN_2048_certificate_der_WHEN_load_x509_certificates_THEN_succeed() throws Exception {
        String certificatePath = ENCRYPTION_RESOURCE_PATH.resolve("certificate-2048.der").toString();

        List<X509Certificate> certificateList = EncryptionUtils.loadX509Certificates(certificatePath);

        assertThat(certificateList.size(), is(1));
        assertThat(certificateList.get(0), notNullValue());
        assertThat(certificateList.get(0).getType(), is("X.509"));
    }

    @Test
    void GIVEN_2048_pkcs8_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        String privateKeyPath = ENCRYPTION_RESOURCE_PATH.resolve("pkcs8-2048.pem").toString();

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_2048_pkcs8_der_WHEN_load_private_key_THEN_succeed() throws Exception {
        String privateKeyPath = ENCRYPTION_RESOURCE_PATH.resolve("pkcs8-2048.der").toString();

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_2048_pkcs1_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        String privateKeyPath = ENCRYPTION_RESOURCE_PATH.resolve("pkcs1-2048.pem").toString();

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_1024_pkcs1_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        String privateKeyPath = ENCRYPTION_RESOURCE_PATH.resolve("pkcs1-1024.pem").toString();

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_4096_pkcs1_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        String privateKeyPath = ENCRYPTION_RESOURCE_PATH.resolve("pkcs1-4096.pem").toString();

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_512_pkcs1_pem_WHEN_load_private_key_THEN_succeed() throws Exception {
        String privateKeyPath = ENCRYPTION_RESOURCE_PATH.resolve("pkcs1-512.pem").toString();

        PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

        assertThat(privateKey, notNullValue());
        assertThat(privateKey.getAlgorithm(), is("RSA"));
    }

    @Test
    void GIVEN_key_invalid_WHEN_load_private_key_THEN_throw_exception() {
        String privateKeyPath = ENCRYPTION_RESOURCE_PATH.resolve("invalid-key.pem").toString();

        assertThrows(GeneralSecurityException.class, () -> EncryptionUtils.loadPrivateKey(privateKeyPath));
    }

    @Test
    void GIVEN_key_file_not_exist_WHEN_load_private_key_THEN_throw_exception() {
        String privateKeyPath = ENCRYPTION_RESOURCE_PATH.resolve("some-key.pem").toString();

        assertThrows(IOException.class, () -> EncryptionUtils.loadPrivateKey(privateKeyPath));
    }
}
