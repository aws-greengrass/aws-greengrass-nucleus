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
import lombok.AccessLevel;
import lombok.Getter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.net.ssl.KeyManager;

public class SecurityService {
    private static final Logger logger = LogManager.getLogger(SecurityService.class);
    private static final String KEY_TYPE = "keyType";
    private static final String KEY_URI = "keyUri";

    @Getter(AccessLevel.PACKAGE)
    private final ConcurrentMap<CaseInsensitiveString, CryptoKeySpi> cryptoKeyProviderMap;

    public SecurityService() {
        this.cryptoKeyProviderMap = new ConcurrentHashMap<>();
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
            logger.atInfo().kv(KEY_TYPE, keyType).log("Crypto key service provider is either removed or not the same"
                    + " provider");
        }
    }

    /**
     * Get JSSE KeyManagers, used for https TLS handshake.
     *
     * @param keyUri private key URI
     * @return KeyManagers that manage the specified private key
     * @throws ServiceUnavailableException if crypto key provider service is unavailable
     * @throws KeyLoadingException if crypto key provider service fails to load key
     * @throws URISyntaxException if key URI syntax error
     */
    public KeyManager[] getKeyManagers(String keyUri)
            throws ServiceUnavailableException, KeyLoadingException, URISyntaxException {
        logger.atTrace().kv(KEY_URI, keyUri).log("Get key managers by key URI");
        CryptoKeySpi provider = selectCryptoKeyProvider(keyUri);
        return provider.getKeyManagers(keyUri);
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
}
