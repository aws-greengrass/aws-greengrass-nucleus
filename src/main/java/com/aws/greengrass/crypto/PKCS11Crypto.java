/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.crypto;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import sun.security.pkcs11.SunPKCS11;
import sun.security.pkcs11.wrapper.PKCS11;
import sun.security.pkcs11.wrapper.PKCS11Constants;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

public class PKCS11Crypto extends CryptoI.Default implements CryptoI {
    private static final Logger LOG = LogManager.getLogger(PKCS11Crypto.class);
    protected SunPKCS11 pkcs11;

    @Inject
    public PKCS11Crypto(DeviceConfiguration deviceConfiguration) {
        super(deviceConfiguration);
        try (InputStream bis = new ByteArrayInputStream(
                ("library=/usr/local/Cellar/softhsm/2.6.1/lib/softhsm/libsofthsm2"
                        + ".so\nslot=306401190\nname=abc_me\n").getBytes(StandardCharsets.UTF_8))) {
            this.pkcs11 = new SunPKCS11(bis);
            Security.addProvider(pkcs11);
        } catch (IOException ignored) {
        }
    }

    @Override
    protected X509TrustManager[] createTrustManagers(String rootCAPath) throws TLSAuthException {
        return super.createTrustManagers(rootCAPath);
    }

    @Override
    protected X509KeyManager[] createKeyManagers(String privateKeyPath, String certificatePath)
            throws TLSAuthException {
        char[] pin = "1234".toCharArray();
        try {
            KeyStore ks = KeyStore.getInstance("PKCS11", pkcs11);
            ks.load(null, pin);
            List<String> aliases = Collections.list(ks.aliases());
            if (aliases.isEmpty()) {
                LOG.error("Unable to find any PKCS#11 private keys. A private key must be matched with a certificate "
                        + "having the same ID in the same slot in order to be loaded.");
                throw new TLSAuthException("No PKCS#11 keys found");
            }

            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(ks, pin);
            return Arrays.stream(keyManagerFactory.getKeyManagers()).map(x -> (X509KeyManager) x)
                    .toArray(X509KeyManager[]::new);
        } catch (IOException | NoSuchAlgorithmException | CertificateException | KeyStoreException | UnrecoverableKeyException e) {
            throw new TLSAuthException("Failed to get key manager", e);
        }
    }

    @Override
    public void close() throws Exception {
        // Needed to prevent JVM crash on shutdown
        PKCS11.getInstance("/usr/local/Cellar/softhsm/2.6.1/lib/softhsm/libsofthsm2.so", null, null, true).C_Finalize(PKCS11Constants.NULL_PTR);
    }
}
