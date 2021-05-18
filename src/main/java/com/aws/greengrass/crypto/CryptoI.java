/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.crypto;

import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.EncryptionUtils;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

public interface CryptoI extends AutoCloseable {
    AwsIotMqttConnectionBuilder getIotMqttConnectionBuilder();

    X509KeyManager[] getKeyManagers() throws TLSAuthException;

    X509TrustManager[] getTrustManagers() throws TLSAuthException;

    void setImplementationUpdated(Runnable implChanged);

    class Default implements CryptoI {
        protected final String certificatePath;
        protected final String privateKeyPath;
        protected final String rootCAPath;
        protected X509KeyManager[] keyManagers = null;
        protected X509TrustManager[] trustManagers = null;
        protected Runnable implChanged;
        @SuppressWarnings("PMD.NullAssignment")
        protected final Subscriber subscriber = (what, topic) -> {
            if (!WhatHappened.timestampUpdated.equals(what) && !WhatHappened.initialized.equals(what)
                    && implChanged != null) {
                synchronized (this) {
                    keyManagers = null;
                    trustManagers = null;
                }
                implChanged.run();
            }
        };

        @Inject
        public Default(DeviceConfiguration deviceConfiguration) {
            certificatePath = Coerce.toString(deviceConfiguration.getCertificateFilePath().subscribe(subscriber));
            privateKeyPath = Coerce.toString(deviceConfiguration.getPrivateKeyFilePath().subscribe(subscriber));
            rootCAPath = Coerce.toString(deviceConfiguration.getRootCAFilePath().subscribe(subscriber));
        }

        @Override
        public AwsIotMqttConnectionBuilder getIotMqttConnectionBuilder() {
            return AwsIotMqttConnectionBuilder.newMtlsBuilderFromPath(certificatePath, privateKeyPath)
                    .withCertificateAuthorityFromPath(null, rootCAPath);
        }

        @Override
        public synchronized X509KeyManager[] getKeyManagers() throws TLSAuthException {
            if (keyManagers == null) {
                keyManagers = createKeyManagers(privateKeyPath, certificatePath);
            }
            return keyManagers;
        }

        @Override
        public synchronized X509TrustManager[] getTrustManagers() throws TLSAuthException {
            if (trustManagers == null) {
                trustManagers = createTrustManagers(rootCAPath);
            }
            return trustManagers;
        }

        @Override
        public void setImplementationUpdated(Runnable implChanged) {
            this.implChanged = implChanged;
        }

        protected X509TrustManager[] createTrustManagers(String rootCAPath) throws TLSAuthException {
            try {
                List<X509Certificate> trustCertificates = EncryptionUtils.loadX509Certificates(rootCAPath);

                KeyStore tmKeyStore = KeyStore.getInstance("JKS");
                tmKeyStore.load(null, null);
                for (X509Certificate certificate : trustCertificates) {
                    X500Principal principal = certificate.getSubjectX500Principal();
                    String name = principal.getName("RFC2253");
                    tmKeyStore.setCertificateEntry(name, certificate);
                }
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
                trustManagerFactory.init(tmKeyStore);
                return Arrays.stream(trustManagerFactory.getTrustManagers()).map(x -> (X509TrustManager) x)
                        .toArray(X509TrustManager[]::new);
            } catch (GeneralSecurityException | IOException e) {
                throw new TLSAuthException("Failed to get trust manager", e);
            }
        }

        protected X509KeyManager[] createKeyManagers(String privateKeyPath, String certificatePath)
                throws TLSAuthException {
            try {
                List<X509Certificate> certificateChain = EncryptionUtils.loadX509Certificates(certificatePath);

                PrivateKey privateKey = EncryptionUtils.loadPrivateKey(privateKeyPath);

                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(null);
                keyStore.setKeyEntry("private-key", privateKey, null, certificateChain.toArray(new Certificate[0]));

                KeyManagerFactory keyManagerFactory =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, null);
                return Arrays.stream(keyManagerFactory.getKeyManagers()).map(x -> (X509KeyManager) x)
                        .toArray(X509KeyManager[]::new);
            } catch (GeneralSecurityException | IOException e) {
                throw new TLSAuthException("Failed to get key manager", e);
            }
        }

        @Override
        public void close() throws Exception {
        }
    }
}
