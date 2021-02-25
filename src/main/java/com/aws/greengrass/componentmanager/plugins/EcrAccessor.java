/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.plugins.exceptions.RegistryAuthException;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.tes.LazyCredentialProvider;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.ProxyUtils;
import lombok.AllArgsConstructor;
import org.apache.commons.codec.binary.Base64;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.ecr.model.ServerException;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.inject.Inject;

/**
 * AWS ECR SDK client wrapper.
 *
 */
@AllArgsConstructor
public class EcrAccessor {
    private EcrClient ecrClient;

    /**
     * Constructor.
     *
     * @param deviceConfiguration Device config
     * @param lazyCredentialProvider AWS credentials provider
     */
    @Inject
    public EcrAccessor(DeviceConfiguration deviceConfiguration, LazyCredentialProvider lazyCredentialProvider) {
        configureClient(deviceConfiguration, lazyCredentialProvider);
        deviceConfiguration.getAWSRegion()
                .subscribe((what, node) -> configureClient(deviceConfiguration, lazyCredentialProvider));
    }

    private void configureClient(DeviceConfiguration deviceConfiguration,
                                 LazyCredentialProvider lazyCredentialProvider) {
        this.ecrClient = EcrClient.builder().httpClient(ProxyUtils.getSdkHttpClient())
                .region(Region.of(Coerce.toString(deviceConfiguration.getAWSRegion())))
                .credentialsProvider(lazyCredentialProvider).build();
    }

    /**
     * Get credentials(auth token) for a private docker registry in ECR.
     *
     * @param registryId Registry id
     * @return Registry.Credentials - Registry's authorization information
     * @throws RegistryAuthException When authentication fails
     */
    @SuppressWarnings("PMD.AvoidRethrowingException")
    public Registry.Credentials getCredentials(String registryId) throws RegistryAuthException {
        try {
            AuthorizationData authorizationData = ecrClient.getAuthorizationToken(
                    GetAuthorizationTokenRequest.builder().registryIds(Collections.singletonList(registryId)).build())
                    .authorizationData().get(0);
            // Decoded auth token is of the format <username>:<password>
            String[] authTokenParts =
                    new String(Base64.decodeBase64(authorizationData.authorizationToken()), StandardCharsets.UTF_8)
                            .split(":");
            return new Registry.Credentials(authTokenParts[0], authTokenParts[1],
                    authorizationData.expiresAt());
        } catch (ServerException | SdkClientException e) {
            // Errors we can retry on
            throw e;
        } catch (EcrException e) {
            throw new RegistryAuthException(String.format("Failed to get credentials for ECR registry - %s",
                    registryId), e);
        }
    }
}
