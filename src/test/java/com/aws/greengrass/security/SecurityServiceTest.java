/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.security;

import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.security.exceptions.KeyLoadingException;
import com.aws.greengrass.security.exceptions.ServiceProviderConflictException;
import com.aws.greengrass.security.exceptions.ServiceUnavailableException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.EncryptionUtilsTest;
import org.hamcrest.collection.IsMapContaining;
import org.hamcrest.collection.IsMapWithSize;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.nio.file.Path;
import javax.net.ssl.KeyManager;
import javax.net.ssl.X509KeyManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class SecurityServiceTest {

    private final SecurityService service = new SecurityService(mock(DeviceConfiguration.class));

    private final SecurityService.DefaultCryptoKeyProvider defaultProvider =
            new SecurityService.DefaultCryptoKeyProvider();

    @Mock
    private CryptoKeySpi mockKeyProvider;

    @TempDir
    protected static Path resourcePath;

    @Test
    void GIVEN_key_service_provider_WHEN_register_provider_THEN_succeed() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("PKCS11");
        service.registerCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(2));
        assertThat(service.getCryptoKeyProviderMap(),
                IsMapContaining.hasEntry(new CaseInsensitiveString("pkcs11"), mockKeyProvider));
        CryptoKeySpi providerB = mock(CryptoKeySpi.class);
        when(providerB.supportedKeyType()).thenReturn("PARSEC");
        service.registerCryptoKeyProvider(providerB);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(3));
        assertThat(service.getCryptoKeyProviderMap(),
                IsMapContaining.hasEntry(new CaseInsensitiveString("parsec"), providerB));
    }

    @Test
    void GIVEN_key_service_provider_WHEN_register_provider_multiple_time_THEN_api_idempotent() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("PKCS11");
        service.registerCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(2));
        service.registerCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(2));
        assertThat(service.getCryptoKeyProviderMap(),
                IsMapContaining.hasEntry(new CaseInsensitiveString("pkcs11"), mockKeyProvider));
    }

    @Test
    void GIVEN_key_service_providers_with_same_key_type_WHEN_register_provider_THEN_throw_exception() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("PKCS11");
        service.registerCryptoKeyProvider(mockKeyProvider);
        CryptoKeySpi providerB = mock(CryptoKeySpi.class);
        when(providerB.supportedKeyType()).thenReturn("pkcs11");
        assertThrows(ServiceProviderConflictException.class, () -> service.registerCryptoKeyProvider(providerB));
    }

    @Test
    void GIVEN_key_service_provider_registered_WHEN_deregister_THEN_removed() throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("PKCS11");
        service.registerCryptoKeyProvider(mockKeyProvider);
        service.deregisterCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(1));
        // validate idempotency
        service.deregisterCryptoKeyProvider(mockKeyProvider);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(1));
    }

    @Test
    void GIVEN_key_service_provider_registered_WHEN_deregister_a_different_instance_THEN_the_provider_not_removed()
            throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("PKCS11");
        service.registerCryptoKeyProvider(mockKeyProvider);
        CryptoKeySpi providerB = mock(CryptoKeySpi.class);
        when(providerB.supportedKeyType()).thenReturn("pkcs11");
        service.deregisterCryptoKeyProvider(providerB);
        assertThat(service.getCryptoKeyProviderMap(), IsMapWithSize.aMapWithSize(2));
        assertThat(service.getCryptoKeyProviderMap(),
                IsMapContaining.hasEntry(new CaseInsensitiveString("pkcs11"), mockKeyProvider));
    }

    @Test
    void GIVEN_key_service_provider_registered_WHEN_get_key_managers_THEN_delegate_call_to_service_provider()
            throws Exception {
        when(mockKeyProvider.supportedKeyType()).thenReturn("PKCS11");
        URI keyUri = new URI("pkcs11:object=key-label");
        URI certificateUri = new URI("file:///path/to/certificate");
        KeyManager[] mockKeyManagers = {mock(KeyManager.class)};
        when(mockKeyProvider.getKeyManagers(keyUri, certificateUri)).thenReturn(mockKeyManagers);
        service.registerCryptoKeyProvider(mockKeyProvider);
        KeyManager[] keyManagers = service.getKeyManagers(keyUri, certificateUri);
        assertThat(keyManagers, Is.is(mockKeyManagers));
    }

    @Test
    void GIVEN_key_service_provider_not_registered_WHEN_get_key_managers_THEN_throw_exception() {
        assertThrows(ServiceUnavailableException.class,
                () -> service.getKeyManagers(new URI("pkcs11:object=key-label"), new URI("file:///path/to/certificate")));
    }

    @Test
    void GIVEN_key_and_cert_uri_WHEN_get_key_managers_from_default_THEN_succeed() throws Exception {
        Path certPath =
                EncryptionUtilsTest.generateCertificateFile(2048, true, resourcePath.resolve("certificate.pem"),
                        false).getLeft();
        Path privateKeyPath =
                EncryptionUtilsTest.generatePkCS8PrivateKeyFile(2048, true, resourcePath.resolve("privateKey.pem"),
                        false);

        KeyManager[] keyManagers =
                defaultProvider.getKeyManagers(privateKeyPath.toUri(), certPath.toUri());
        assertThat(keyManagers.length, is(1));
        X509KeyManager keyManager = (X509KeyManager) keyManagers[0];
        assertThat(keyManager.getPrivateKey("private-key"), notNullValue());
        assertThat(keyManager.getPrivateKey("private-key").getAlgorithm(), is("RSA"));
        assertThat(keyManager.getCertificateChain("private-key").length, is(1));
        assertThat(keyManager.getCertificateChain("private-key")[0].getSigAlgName(), is("SHA256withRSA"));

        privateKeyPath =
                EncryptionUtilsTest.generatePkCS8PrivateKeyFile(256, true, resourcePath.resolve("privateKey.pem"),
                        true);

        keyManagers =
                defaultProvider.getKeyManagers(privateKeyPath.toUri(), certPath.toUri());
        assertThat(keyManagers.length, is(1));
        keyManager = (X509KeyManager) keyManagers[0];
        assertThat(keyManager.getPrivateKey("private-key"), notNullValue());
        assertThat(keyManager.getPrivateKey("private-key").getAlgorithm(), is("EC"));

        privateKeyPath =
                EncryptionUtilsTest.generatePkCS8PrivateKeyFile(256, false, resourcePath.resolve("privateKey.der"),
                        true);

        keyManagers =
                defaultProvider.getKeyManagers(privateKeyPath.toUri(), certPath.toUri());
        assertThat(keyManagers.length, is(1));
        keyManager = (X509KeyManager) keyManagers[0];
        assertThat(keyManager.getPrivateKey("private-key"), notNullValue());
        assertThat(keyManager.getPrivateKey("private-key").getAlgorithm(), is("EC"));
    }

    @Test
    void GIVEN_non_compatible_key_uri_WHEN_get_key_managers_from_default_THEN_throw_exception() {
        Exception e = assertThrows(KeyLoadingException.class,
                () -> defaultProvider.getKeyManagers(new URI("pkcs11:object=key-label"), new URI("file:///path")));
        assertThat(e.getMessage(), containsString("Only support file type private key"));
    }

    @Test
    void GIVEN_non_compatible_cert_uri_WHEN_get_key_managers_from_default_THEN_throw_exception() throws Exception {
        Path privateKeyPath = resourcePath.resolve("good-key.pem");
        EncryptionUtilsTest.generatePkCS8PrivateKeyFile(2048, true, privateKeyPath, false);
        Exception e = assertThrows(KeyLoadingException.class,
                () -> defaultProvider.getKeyManagers(privateKeyPath.toFile().toURI(),
                        new URI("pkcs11:object=key-label")));
        assertThat(e.getMessage(), containsString("Only support file type certificate"));
    }

    @Test
    void GIVEN_invalid_key_PEM_WHEN_get_key_managers_from_default_THEN_throw_exception() throws Exception {
        Path privateKeyPath = resourcePath.resolve("invalid-key.pem");
        EncryptionUtilsTest.writePemFile("RSA PRIVATE KEY", "this is private key".getBytes(), privateKeyPath);
        Exception e = assertThrows(KeyLoadingException.class,
                () -> defaultProvider.getKeyManagers(privateKeyPath.toUri(), new URI("file:///path/to/certificate")));
        assertThat(e.getMessage(), containsString( "Failed to get keypair"));
    }
}
