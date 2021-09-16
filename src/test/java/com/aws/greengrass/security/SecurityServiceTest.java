/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.security;

import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.security.exceptions.ServiceProviderConflictException;
import com.aws.greengrass.security.exceptions.ServiceUnavailableException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.hamcrest.collection.IsMapContaining;
import org.hamcrest.collection.IsMapWithSize;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.KeyManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class SecurityServiceTest {

    private final SecurityService service = new SecurityService();

    @Mock
    private CryptoKeySpi mockKeyProvider;

    @Test
    void GIVEN_key_service_provider_WHEN_register_provider_THEN_succeed() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("FILE");
        service.registerCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(1));
        assertThat(service.getCryptoKeyProviderMap(), IsMapContaining.hasEntry(new CaseInsensitiveString("file"),
                mockKeyProvider));
        CryptoKeySpi providerB = mock(CryptoKeySpi.class);
        when(providerB.supportedKeyType()).thenReturn("PKCS11");
        service.registerCryptoKeyProvider(providerB);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(2));
        assertThat(service.getCryptoKeyProviderMap(), IsMapContaining.hasEntry(new CaseInsensitiveString("pkcs11"),
                providerB));
    }

    @Test
    void GIVEN_key_service_provider_WHEN_register_provider_multiple_time_THEN_api_idempotent() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("FILE");
        service.registerCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(1));
        service.registerCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(1));
        assertThat(service.getCryptoKeyProviderMap(), IsMapContaining.hasEntry(new CaseInsensitiveString("file"),
                mockKeyProvider));
    }

    @Test
    void GIVEN_key_service_providers_with_same_key_type_WHEN_register_provider_THEN_throw_exception() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("FILE");
        service.registerCryptoKeyProvider(mockKeyProvider);
        CryptoKeySpi providerB = mock(CryptoKeySpi.class);
        when(providerB.supportedKeyType()).thenReturn("file");
        assertThrows(ServiceProviderConflictException.class, () -> service.registerCryptoKeyProvider(providerB));
    }

    @Test
    void GIVEN_key_service_provider_registered_WHEN_deregister_THEN_removed() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("FILE");
        service.registerCryptoKeyProvider(mockKeyProvider);
        service.deregisterCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(0));
        // validate idempotency
        service.deregisterCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(0));
    }

    @Test
    void GIVEN_key_service_provider_registered_WHEN_deregister_a_different_instance_THEN_the_provider_not_removed() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("FILE");
        service.registerCryptoKeyProvider(mockKeyProvider);
        CryptoKeySpi providerB = mock(CryptoKeySpi.class);
        when(providerB.supportedKeyType()).thenReturn("file");
        service.deregisterCryptoKeyProvider(providerB);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(1));
        assertThat(service.getCryptoKeyProviderMap(), IsMapContaining.hasEntry(new CaseInsensitiveString("file"),
                mockKeyProvider));
    }

    @Test
    void GIVEN_key_service_provider_registered_WHEN_get_key_managers_THEN_delegate_call_to_service_provider() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("FILE");
        String keyUri = "file:///path/to/file";
        KeyManager[] mockKeyManagers = {mock(KeyManager.class)};
        when(mockKeyProvider.getKeyManagers(keyUri)).thenReturn(mockKeyManagers);
        service.registerCryptoKeyProvider(mockKeyProvider);
        KeyManager[] keyManagers = service.getKeyManagers(keyUri);
        assertThat(keyManagers, Is.is(mockKeyManagers));
    }

    @Test
    void GIVEN_key_service_provider_not_registered_WHEN_get_key_managers_THEN_throw_exception () {
        assertThrows(ServiceUnavailableException.class, () -> service.getKeyManagers("file:///path/to/file"));
    }
}