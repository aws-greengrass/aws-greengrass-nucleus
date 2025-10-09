/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.tes.LazyCredentialProvider;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static com.aws.greengrass.deployment.DeviceConfiguration.S3_ENDPOINT_PROP_NAME;

@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
public class S3SdkClientFactoryTest {
    private final Context context = new Context();
    private S3Client cachedClient;

    @Mock
    DeviceConfiguration deviceConfig;

    @Mock
    LazyCredentialProvider credentialProvider;

    static final DeviceConfigurationException error = new DeviceConfigurationException("test");

    @Mock
    Topic regionTopic;

    @BeforeEach
    void setupTopics() {
        lenient().when(regionTopic.getOnce()).thenReturn("us-west-2");
        lenient().doAnswer(a -> regionTopic).when(deviceConfig).getAWSRegion();
    }

    @AfterEach
    void clearCache() {
        S3SdkClientFactory.clientCache.clear();
        if (cachedClient != null) {
            cachedClient.close();
        }
        System.clearProperty(S3_ENDPOINT_PROP_NAME);
    }

    @Test
    void GIVEN_valid_configuration_WHEN_get_client_THEN_client_returned() throws DeviceConfigurationException {
        S3SdkClientFactory factory = new S3SdkClientFactory(deviceConfig, credentialProvider);
        factory.handleRegionUpdate(); // simulate topics firing during initialization

        try (S3Client client = factory.getS3Client()) {
            assertThat("has client", client, is(notNullValue()));
            assertThat(S3SdkClientFactory.clientCache, hasEntry(is(Region.US_WEST_2), is(client)));
            assertThat("no validation error", factory.getConfigValidationError(), is(nullValue()));
        }
    }

    @Test
    void GIVEN_invalid_configuration_WHEN_get_client_THEN_exception_thrown() throws DeviceConfigurationException {
        doThrow(error).when(deviceConfig).validate();

        S3SdkClientFactory factory = new S3SdkClientFactory(deviceConfig, credentialProvider);

        factory.handleRegionUpdate(); // simulate topics firing during initialization

        assertThrowsExactly(DeviceConfigurationException.class, factory::getS3Client, "test");
        assertThat(factory.getConfigValidationError(), is("test"));
    }

    @Test
    void GIVEN_valid_configuration_WHEN_updated_THEN_new_added() throws DeviceConfigurationException {
        when(regionTopic.getOnce()).thenReturn("us-west-2").thenReturn("eu-central-1");

        S3SdkClientFactory factory = new S3SdkClientFactory(deviceConfig, credentialProvider);

        factory.handleRegionUpdate();   // simulate topics firing during initialization

        try (S3Client client = factory.getS3Client()) {
            assertThat("has client", client, is(notNullValue()));
            assertThat(S3SdkClientFactory.clientCache, hasEntry(is(Region.US_WEST_2), is(client)));

            factory.handleRegionUpdate(); // simulate topics firing after config update
            try (S3Client client2 = factory.getS3Client()) {
                assertThat(client2, is(not(client)));
                assertThat(S3SdkClientFactory.clientCache, hasEntry(is(Region.EU_CENTRAL_1), is(client2)));
            }
        }
    }

    @Test
    void GIVEN_valid_configuration_WHEN_get_client_for_region_THEN_clients_cached() {
        S3SdkClientFactory factory = new S3SdkClientFactory(deviceConfig, credentialProvider);
        factory.handleRegionUpdate();
        try (S3Client client = factory.getClientForRegion(Region.US_WEST_2)) {
            assertThat("has client", client, is(notNullValue()));
            assertThat(S3SdkClientFactory.clientCache, hasEntry(is(Region.US_WEST_2), is(client)));

            try (S3Client client2 = factory.getClientForRegion(Region.EU_CENTRAL_1)) {
                assertThat("has client", client, is(notNullValue()));
                assertThat(client2, is(not(client)));
                assertThat(S3SdkClientFactory.clientCache, hasEntry(is(Region.US_WEST_2), is(client)));
                assertThat(S3SdkClientFactory.clientCache, hasEntry(is(Region.EU_CENTRAL_1), is(client2)));

                try (S3Client client3 = factory.getClientForRegion(Region.US_WEST_2)) {
                    assertThat("has client", client, is(notNullValue()));
                    assertThat(client3, is(client));
                    assertThat(S3SdkClientFactory.clientCache, hasEntry(is(Region.US_WEST_2), is(client)));
                    assertThat(S3SdkClientFactory.clientCache, hasEntry(is(Region.EU_CENTRAL_1), is(client2)));
                }
            }
        }
    }

    @Test
    void GIVEN_regional_device_configuration_WHEN_get_client_for_region_THEN_client_uses_regional_endpoint()
            throws DeviceConfigurationException {
        S3SdkClientFactory factory = new S3SdkClientFactory(deviceConfig, credentialProvider);
        factory.handleRegionUpdate();
        assertEquals(null, System.getProperty(S3_ENDPOINT_PROP_NAME));
        Topic s3Endpoint = Topic.of(context, DeviceConfiguration.S3_ENDPOINT_TYPE, "REGIONAL");
        when(deviceConfig.gets3EndpointType()).thenReturn(s3Endpoint);
        try (S3Client client = factory.getS3Client()) {
            assertThat("has client", client, is(notNullValue()));
            assertEquals("regional", System.getProperty(S3_ENDPOINT_PROP_NAME));
        }
    }

    @Test
    void GIVEN_global_device_config_WHEN_get_client_THEN_clients_change_to_global_endpoint_and_update_cache()
            throws DeviceConfigurationException {
        S3SdkClientFactory factory = new S3SdkClientFactory(deviceConfig, credentialProvider);
        factory.handleRegionUpdate();

        Topic s3Endpoint = Topic.of(context, DeviceConfiguration.S3_ENDPOINT_TYPE, "REGIONAL");
        when(deviceConfig.gets3EndpointType()).thenReturn(s3Endpoint);
        try (S3Client client = factory.getS3Client()) {
            cachedClient = client;
            assertThat("has client", client, is(notNullValue()));
            assertEquals("regional", System.getProperty(S3_ENDPOINT_PROP_NAME));
        }

        s3Endpoint = Topic.of(context, DeviceConfiguration.S3_ENDPOINT_TYPE, "GLOBAL");
        when(deviceConfig.gets3EndpointType()).thenReturn(s3Endpoint);

        try (S3Client client = factory.getS3Client()) {
            assertThat("has client", client, is(notNullValue()));
            assertThat(client, is(not(cachedClient)));
            assertEquals(null, System.getProperty(S3_ENDPOINT_PROP_NAME));
        }
    }

    @Test
    void GIVEN_regional_device_config_and_regional_system_property_WHEN_get_client_THEN_client_does_not_update()
            throws DeviceConfigurationException {
        S3SdkClientFactory factory = new S3SdkClientFactory(deviceConfig, credentialProvider);
        factory.handleRegionUpdate();
        System.setProperty(S3_ENDPOINT_PROP_NAME, "regional");
        Topic s3Endpoint = Topic.of(context, DeviceConfiguration.S3_ENDPOINT_TYPE, "REGIONAL");
        when(deviceConfig.gets3EndpointType()).thenReturn(s3Endpoint);
        try (S3Client client = factory.getS3Client()) {
            cachedClient = client;
            assertThat("has client", client, is(notNullValue()));
            assertEquals("regional", System.getProperty(S3_ENDPOINT_PROP_NAME));
        }
        try (S3Client client = factory.getS3Client()) {
            assertEquals(client, cachedClient);
            assertEquals("regional", System.getProperty(S3_ENDPOINT_PROP_NAME));
        }
    }
}
