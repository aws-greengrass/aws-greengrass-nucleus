/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.security;

import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.security.exceptions.KeyLoadingException;
import com.aws.greengrass.security.exceptions.ServiceProviderConflictException;
import com.aws.greengrass.security.exceptions.ServiceUnavailableException;
import com.aws.greengrass.util.EncryptionUtils;
import lombok.AccessLevel;
import lombok.Getter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;

public final class SecurityService {
    private static final Logger logger = LogManager.getLogger(SecurityService.class);
    private static final String KEY_TYPE = "keyType";
    private static final String KEY_URI = "keyUri";
    private static final String CERT_URI = "certificateUri";

    @Getter(AccessLevel.PACKAGE)
    private final ConcurrentMap<CaseInsensitiveString, CryptoKeySpi> cryptoKeyProviderMap = new ConcurrentHashMap<>();

    /**
     * Constructor of security service.
     */
    public SecurityService() {
        // instantiate and register the default file based provider
        CryptoKeySpi defaultProvider = new DefaultCryptoKeyProvider();
        try {
            this.registerCryptoKeyProvider(defaultProvider);
        } catch (ServiceProviderConflictException e) {
            // it won't happen, it's programming error if it does
            throw new RuntimeException("Default crypto key provider has been registered", e);
        }
    }

    /**
     * Register crypto key provider for the key type.
     *
     * @param keyProvider Crypto key provider
     * @throws ServiceProviderConflictException if key type is already registered
     */
    public void registerCryptoKeyProvider(CryptoKeySpi keyProvider) throws ServiceProviderConflictException {
        CaseInsensitiveString keyType = new CaseInsensitiveString(keyProvider.supportedKeyType());
        logger.atInfo().kv(KEY_TYPE, keyType).log("Register crypto key service provider");
        CryptoKeySpi provider = cryptoKeyProviderMap.computeIfAbsent(keyType, k -> keyProvider);
        if (!provider.equals(keyProvider)) {
            logger.atError().kv(KEY_TYPE, keyType)
                    .log("Crypto key service provider for the key type is already registered");
            throw new ServiceProviderConflictException(String.format("Key type %s provider is registered", keyType));
        }
    }

    /**
     * Deregister crypto key provide for the key type.
     *
     * @param keyProvider Crypto key provider
     */
    public void deregisterCryptoKeyProvider(CryptoKeySpi keyProvider) {
        CaseInsensitiveString keyType = new CaseInsensitiveString(keyProvider.supportedKeyType());
        boolean removed = cryptoKeyProviderMap.remove(keyType, keyProvider);
        if (!removed) {
            logger.atInfo().kv(KEY_TYPE, keyType).log("Crypto key service provider is either already removed or "
                    + "unregistered");
        }
    }

    /**
     * Get JSSE KeyManagers, used for https TLS handshake.
     *
     * @param privateKeyUri private key URI
     * @param certificateUri certificate URI
     * @return KeyManagers that manage the specified private key
     * @throws ServiceUnavailableException if crypto key provider service is unavailable
     * @throws KeyLoadingException if crypto key provider service fails to load key
     * @throws URISyntaxException if key URI syntax error
     */
    public KeyManager[] getKeyManagers(String privateKeyUri, String certificateUri)
            throws ServiceUnavailableException, KeyLoadingException, URISyntaxException {
        logger.atTrace().kv(KEY_URI, privateKeyUri).kv(CERT_URI, certificateUri)
                .log("Get key managers by key URI");
        CryptoKeySpi provider = selectCryptoKeyProvider(privateKeyUri);
        return provider.getKeyManagers(privateKeyUri, certificateUri);
    }

    /**
     * Get JCA KeyManagers, used for Secret manager.
     *
     * @param privateKeyUri private key URI
     * @return KeyManagers that manage the specified private key
     * @throws ServiceUnavailableException if crypto key provider service is unavailable
     * @throws KeyLoadingException if crypto key provider service fails to load key
     * @throws URISyntaxException if key URI syntax error
     */
    public KeyPair getKeyPair(String privateKeyUri)
            throws ServiceUnavailableException, KeyLoadingException, URISyntaxException {
        logger.atTrace().kv(KEY_URI, privateKeyUri).log("Get keypair by key URI");
        CryptoKeySpi provider = selectCryptoKeyProvider(privateKeyUri);
        return provider.getKeyPair(privateKeyUri);
    }

    private CryptoKeySpi selectCryptoKeyProvider(String keyUri) throws URISyntaxException, ServiceUnavailableException {
        URI uri = new URI(keyUri);
        CaseInsensitiveString keyType = new CaseInsensitiveString(uri.getScheme());
        CryptoKeySpi provider = cryptoKeyProviderMap.getOrDefault(keyType, null);
        if (provider == null) {
            logger.atError().kv(KEY_TYPE, keyType).log("Crypto key service provider for the key type is unavailable");
            throw new ServiceUnavailableException(String.format("Crypto key service for %s is unavailable", keyType));
        }
        return provider;
    }

    static class DefaultCryptoKeyProvider implements CryptoKeySpi {
        private static final Logger logger = LogManager.getLogger(DefaultCryptoKeyProvider.class);
        private static final String SUPPORT_KEY_TYPE = "file";

        @SuppressWarnings("PMD.PrematureDeclaration")
        @Override
        public KeyManager[] getKeyManagers(String privateKeyUriStr, String certificateUriStr)
                throws KeyLoadingException, URISyntaxException {
            KeyPair keyPair = getKeyPair(privateKeyUriStr);

            URI certificateUri = new URI(certificateUriStr);
            if (!isUriSupportedKeyType(certificateUri)) {
                logger.atError().kv(CERT_URI, certificateUriStr).log("Can't process the certificate type");
                throw new KeyLoadingException(String.format("Only support %s type certificate", supportedKeyType()));
            }

            try {
                PrivateKey privateKey = keyPair.getPrivate();
                List<X509Certificate> certificateChain =
                        EncryptionUtils.loadX509Certificates(Paths.get(certificateUri));

                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(null);
                keyStore.setKeyEntry("private-key", privateKey, null, certificateChain.toArray(new Certificate[0]));

                KeyManagerFactory keyManagerFactory =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, null);
                return keyManagerFactory.getKeyManagers();
            } catch (GeneralSecurityException | IOException e) {
                logger.atError().kv(KEY_URI, privateKeyUriStr).kv(CERT_URI, certificateUriStr)
                        .log("Exception caught during getting key manager");
                throw new KeyLoadingException("Failed to get key manager", e);
            }
        }

        @Override
        public KeyPair getKeyPair(String privateKeyUriStr)
                throws KeyLoadingException, URISyntaxException {
            URI privateKeyUri = new URI(privateKeyUriStr);
            if (!isUriSupportedKeyType(privateKeyUri)) {
                logger.atError().kv(KEY_URI, privateKeyUriStr).log("Can't process the key type");
                throw new KeyLoadingException(String.format("Only support %s type private key", supportedKeyType()));
            }
            try {
                return EncryptionUtils.loadPrivateKeyPair(Paths.get(privateKeyUri));
            } catch (IOException | GeneralSecurityException e) {
                logger.atError().kv(KEY_URI, privateKeyUriStr)
                        .log("Exception caught during getting keypair");
                throw new KeyLoadingException("Failed to get keypair", e);
            }
        }

        @Override
        public String supportedKeyType() {
            return SUPPORT_KEY_TYPE;
        }

        private boolean isUriSupportedKeyType(URI uri) {
            return new CaseInsensitiveString(supportedKeyType()).equals(new CaseInsensitiveString(uri.getScheme()));
        }
    }
}
