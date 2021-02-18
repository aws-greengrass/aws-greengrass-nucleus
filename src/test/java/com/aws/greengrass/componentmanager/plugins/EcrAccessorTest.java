/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.plugins.exceptions.RegistryAuthException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class EcrAccessorTest {
    @Mock
    private EcrClient ecrClient;

    private EcrAccessor ecrAccessor;

    @BeforeEach
    public void setup() {
        ecrAccessor = new EcrAccessor(ecrClient);
    }

    @Test
    void GIVEN_ecr_accessor_WHEN_get_credentials_success_THEN_return_registry_credentials() throws Exception {
        Instant credentialsExpiry = Instant.now().plusSeconds(10);
        AuthorizationData authorizationData = AuthorizationData.builder()
                .authorizationToken(Base64.encode("username:password".getBytes(StandardCharsets.UTF_8)))
                .expiresAt(credentialsExpiry).build();
        GetAuthorizationTokenResponse response =
                GetAuthorizationTokenResponse.builder().authorizationData(authorizationData).build();
        when(ecrClient.getAuthorizationToken(any(GetAuthorizationTokenRequest.class))).thenReturn(response);

        Registry.Credentials credentials = ecrAccessor.getCredentials("some_registry_id");

        assertEquals("username", credentials.getUsername());
        assertEquals("password", credentials.getPassword());
        assertEquals(credentialsExpiry, credentials.getExpiresAt());
        verify(ecrClient).getAuthorizationToken(any(GetAuthorizationTokenRequest.class));
    }

    @Test
    void GIVEN_ecr_accessor_WHEN_get_credentials_failure_THEN_throw_auth_error() throws Exception {
        EcrException ecrException = (EcrException) EcrException.builder().message("Something went wrong").build();
        when(ecrClient.getAuthorizationToken(any(GetAuthorizationTokenRequest.class))).thenThrow(ecrException);

        Throwable err = assertThrows(RegistryAuthException.class, () -> ecrAccessor.getCredentials("some_registry_id"));
        assertThat(err.getMessage(), containsString("Failed to get credentials for ECR registry - some_registry_id"));
        assertThat(err.getCause(), is(instanceOf(EcrException.class)));
        verify(ecrClient).getAuthorizationToken(any(GetAuthorizationTokenRequest.class));
    }
}
