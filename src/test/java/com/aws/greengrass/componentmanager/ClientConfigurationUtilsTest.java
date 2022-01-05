/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.security.exceptions.KeyLoadingException;
import com.aws.greengrass.security.exceptions.ServiceUnavailableException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import org.hamcrest.core.Is;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.net.ssl.KeyManager;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ClientConfigurationUtilsTest {
    @Mock
    private SecurityService securityService;

    @TempDir
    protected static Path resourcePath;

    @BeforeEach
    void setup() throws TLSAuthException {
        when(securityService.getDeviceIdentityKeyManagers()).thenCallRealMethod();
    }

    @Test
    void GIVEN_valid_key_and_certificate_paths_WHEN_create_key_managers_THEN_return_proper_object() throws Exception {
        KeyManager keyManager = mock(KeyManager.class);
        when(securityService.getKeyManagers(any(), any())).thenReturn(new KeyManager[]{keyManager});
        Path keyPath = resourcePath.resolve("path/to/key");
        Path certPath = resourcePath.resolve("path/to/cert");
        when(securityService.getDeviceIdentityPrivateKeyURI()).thenReturn(keyPath.toUri());
        when(securityService.getDeviceIdentityCertificateURI()).thenReturn(certPath.toUri());
        KeyManager[] keyManagers = securityService.getDeviceIdentityKeyManagers();
        assertThat(keyManagers.length, Is.is(1));
        assertThat(keyManagers[0], Is.is(keyManager));
        ArgumentCaptor<URI> keyUriCaptor = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<URI> certUriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(securityService).getKeyManagers(keyUriCaptor.capture(), certUriCaptor.capture());
        assertThat(keyPath.toUri(), Is.is(keyUriCaptor.getValue()));
        assertThat(certPath.toUri(), Is.is(certUriCaptor.getValue()));
    }

    @Test
    void GIVEN_valid_key_and_certificate_URIs_WHEN_create_key_managers_THEN_return_proper_object() throws Exception {
        KeyManager keyManager = mock(KeyManager.class);
        when(securityService.getKeyManagers(any(), any())).thenReturn(new KeyManager[]{keyManager});
        URI keyPath = URI.create("files:///path/to/key");
        URI certPath = URI.create("files:///path/to/cert");
        when(securityService.getDeviceIdentityPrivateKeyURI()).thenReturn(keyPath);
        when(securityService.getDeviceIdentityCertificateURI()).thenReturn(certPath);
        KeyManager[] keyManagers = securityService.getDeviceIdentityKeyManagers();
        assertThat(keyManagers.length, Is.is(1));
        assertThat(keyManagers[0], Is.is(keyManager));
        verify(securityService).getKeyManagers(keyPath, certPath);
    }

    @Test
    void GIVEN_security_service_key_loading_exception_WHEN_create_key_managers_THEN_throw_exception(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, KeyLoadingException.class);
        when(securityService.getKeyManagers(any(), any())).thenThrow(KeyLoadingException.class);
        String keyPath = "pkcs11:object=key-label;type=private";
        String certPath = "/path/to/cert";
        when(securityService.getDeviceIdentityPrivateKeyURI()).thenReturn(new URI(keyPath));
        when(securityService.getDeviceIdentityCertificateURI()).thenReturn(new URI(certPath));
        Exception e =
                assertThrows(TLSAuthException.class, () -> securityService.getDeviceIdentityKeyManagers());
        assertThat(e.getCause(), Is.is(IsInstanceOf.instanceOf(KeyLoadingException.class)));
    }

    @Test
    void GIVEN_security_service_throw_unavailable_exception_WHEN_create_key_managers_THEN_retry_till_succeed(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUnavailableException.class);
        KeyManager keyManager = mock(KeyManager.class);
        when(securityService.getKeyManagers(any(), any())).thenThrow(ServiceUnavailableException.class,
                ServiceUnavailableException.class).thenReturn(new KeyManager[]{keyManager});
        String keyPath = "pkcs11:object=key-label;type=private";
        String certPath = "/path/to/cert";
        when(securityService.getDeviceIdentityPrivateKeyURI()).thenReturn(new URI(keyPath));
        when(securityService.getDeviceIdentityCertificateURI()).thenReturn(Paths.get(certPath).toUri());
        KeyManager[] keyManagers = securityService.getDeviceIdentityKeyManagers();
        assertThat(keyManagers.length, Is.is(1));
        assertThat(keyManagers[0], Is.is(keyManager));
        verify(securityService, times(3))
                .getKeyManagers(new URI(keyPath), Paths.get(certPath).toUri());
    }
}
