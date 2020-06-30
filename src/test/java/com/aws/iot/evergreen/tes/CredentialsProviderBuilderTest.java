package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.auth.credentials.Credentials;
import software.amazon.awssdk.crt.auth.credentials.X509CredentialsProvider;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, EGExtension.class})
class CredentialsProviderBuilderTest {
    @Mock
    DeviceConfiguration deviceConfiguration;

    @Mock
    X509CredentialsProvider.X509CredentialsProviderBuilder mockX509builder;

    @Mock
    X509CredentialsProvider mockCredentialsProvider;

    @Mock
    Credentials mockCredentials;

    private CredentialsProviderBuilder builder;

    private static final String ROLE_ALIAS = "ROLE_ALIAS";

    @BeforeEach
    public void setup() throws DeviceConfigurationException {
        builder = new CredentialsProviderBuilder(deviceConfiguration, mockX509builder);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_builder_and_role_alias_WHEN_get_credentials_THEN_return_credentials() throws AWSIotException {
        when(mockX509builder.build()).thenReturn(mockCredentialsProvider);
        when(mockCredentialsProvider.getCredentials()).thenReturn(CompletableFuture.completedFuture(mockCredentials));
        builder.withRoleAlias(ROLE_ALIAS);
        assertEquals(builder.getCredentials(), mockCredentials);
    }

}