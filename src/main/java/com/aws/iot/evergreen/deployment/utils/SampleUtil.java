/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.utils;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is derived from Iot SDK samples - https://github.com/aws/aws-iot-device-sdk-java/blob/master/
 * aws-iot-device-sdk-java-samples/src/main/java/com/amazonaws/services/iot/client/sample/sampleUtil/SampleUtil.java
 */
public class SampleUtil {

    private static Logger logger = LogManager.getLogger(SampleUtil.class);

    public static class KeyStorePasswordPair {
        public KeyStore keyStore;
        public String keyPassword;

        public KeyStorePasswordPair(KeyStore keyStore, String keyPassword) {
            this.keyStore = keyStore;
            this.keyPassword = keyPassword;
        }
    }

    public static KeyStorePasswordPair getKeyStorePasswordPair(final String certificateFile,
                                                               final String privateKeyFile) {
        return getKeyStorePasswordPair(certificateFile, privateKeyFile, null);
    }

    /**
     * Get Key password pair from certificate, private key and algorithm.
     *
     * @param certificateFile File path for the certificate
     * @param privateKeyFile  File path for the private key
     * @param keyAlgorithm    Algorithm used
     * @return {@link KeyStorePasswordPair}
     */
    public static KeyStorePasswordPair getKeyStorePasswordPair(final String certificateFile,
                                                               final String privateKeyFile, String keyAlgorithm) {
        if (certificateFile == null || privateKeyFile == null) {
            logger.info("Certificate or private key file missing");
            return null;
        }
        logger.info("Cert file: {} Private key: {}", certificateFile, privateKeyFile);

        final PrivateKey privateKey = loadPrivateKeyFromFile(privateKeyFile, keyAlgorithm);

        final List<Certificate> certChain = loadCertificatesFromFile(certificateFile);

        if (certChain == null || privateKey == null) {
            return null;
        }

        return getKeyStorePasswordPair(certChain, privateKey);
    }

    /**
     * Get keystore and password from certificate and private key.
     *
     * @param certificates List of certificates
     * @param privateKey   Private key
     * @return {@link KeyStorePasswordPair}
     */
    public static KeyStorePasswordPair getKeyStorePasswordPair(final List<Certificate> certificates,
                                                               final PrivateKey privateKey) {
        KeyStore keyStore;
        String keyPassword;
        try {
            keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null);

            // randomly generated key password for the key in the KeyStore
            keyPassword = new BigInteger(128, new SecureRandom()).toString(32);

            Certificate[] certChain = new Certificate[certificates.size()];
            certChain = certificates.toArray(certChain);
            keyStore.setKeyEntry("alias", privateKey, keyPassword.toCharArray(), certChain);
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            logger.info("Failed to create key store");
            return null;
        }

        return new KeyStorePasswordPair(keyStore, keyPassword);
    }

    private static List<Certificate> loadCertificatesFromFile(final String filename) {
        File file = new File(filename);
        if (!file.exists()) {
            logger.info("Certificate file: {} is not found", filename);
            return null;
        }

        try (BufferedInputStream stream = new BufferedInputStream(new FileInputStream(file))) {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return new ArrayList<>(certFactory.generateCertificates(stream));
        } catch (IOException | CertificateException e) {
            logger.info("Failed to load certificate file {}", filename);
        }
        return null;
    }

    private static PrivateKey loadPrivateKeyFromFile(final String filename, final String algorithm) {
        PrivateKey privateKey = null;

        File file = new File(filename);
        if (!file.exists()) {
            logger.info("Private key file not found: {}", filename);
            return null;
        }
        try (DataInputStream stream = new DataInputStream(new FileInputStream(file))) {
            privateKey = PrivateKeyReader.getPrivateKey(stream, algorithm);
        } catch (IOException | GeneralSecurityException e) {
            logger.info("Failed to load private key from file {}", filename);
        }

        return privateKey;
    }
}
