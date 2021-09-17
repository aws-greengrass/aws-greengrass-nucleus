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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import javax.net.ssl.KeyManager;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ClientConfigurationUtilsTest {

    @InjectMocks
    private ClientConfigurationUtils configurationUtils;

    @Mock
    private SecurityService securityService;

    @TempDir
    protected static Path resourcePath;

    @Test
    void GIVEN_valid_key_and_certificate_paths_WHEN_create_key_managers_THEN_return_proper_object() throws Exception {
        KeyManager keyManager = mock(KeyManager.class);
        when(securityService.getKeyManagers(anyString(), anyString())).thenReturn(new KeyManager[]{keyManager});
        String keyPath = resourcePath.resolve("path/to/key").toString();
        String certPath = resourcePath.resolve("path/to/cert").toString();
        KeyManager[] keyManagers = configurationUtils.createKeyManagers(keyPath, certPath);
        assertThat(keyManagers.length, Is.is(1));
        assertThat(keyManagers[0], Is.is(keyManager));
        verify(securityService).getKeyManagers("file:" + keyPath, "file:" + certPath);
    }

    @Test
    void GIVEN_valid_key_and_certificate_URIs_WHEN_create_key_managers_THEN_return_proper_object() throws Exception {
        KeyManager keyManager = mock(KeyManager.class);
        when(securityService.getKeyManagers(anyString(), anyString())).thenReturn(new KeyManager[]{keyManager});
        String keyPath = "files:///path/to/key";
        String certPath = "files:///path/to/cert";
        KeyManager[] keyManagers = configurationUtils.createKeyManagers(keyPath, certPath);
        assertThat(keyManagers.length, Is.is(1));
        assertThat(keyManagers[0], Is.is(keyManager));
        verify(securityService).getKeyManagers(keyPath, certPath);
    }

    @Test
    void GIVEN_security_service_key_loading_exception_WHEN_create_key_managers_THEN_throw_exception(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, KeyLoadingException.class);
        when(securityService.getKeyManagers(anyString(), anyString())).thenThrow(KeyLoadingException.class);
        String keyPath = "pkcs11:object=key-label;type=private";
        String certPath = "/path/to/cert";
        Exception e =
                assertThrows(TLSAuthException.class, () -> configurationUtils.createKeyManagers(keyPath, certPath));
        assertThat(e.getCause(), Is.is(IsInstanceOf.instanceOf(KeyLoadingException.class)));
    }

    @Test
    void GIVEN_security_service_throw_unavailable_exception_WHEN_create_key_managers_THEN_retry_till_succeed(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUnavailableException.class);
        KeyManager keyManager = mock(KeyManager.class);
        when(securityService.getKeyManagers(anyString(), anyString())).thenThrow(ServiceUnavailableException.class,
                ServiceUnavailableException.class).thenReturn(new KeyManager[]{keyManager});
        String keyPath = "pkcs11:object=key-label;type=private";
        String certPath = "/path/to/cert";
        KeyManager[] keyManagers = configurationUtils.createKeyManagers(keyPath, certPath);
        assertThat(keyManagers.length, Is.is(1));
        assertThat(keyManagers[0], Is.is(keyManager));
        verify(securityService, times(3)).getKeyManagers(keyPath, "file:" + certPath);
    }
}