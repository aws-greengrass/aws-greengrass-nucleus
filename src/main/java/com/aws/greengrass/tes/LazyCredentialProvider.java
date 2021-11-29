/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;

import javax.inject.Inject;
import javax.inject.Provider;

public class LazyCredentialProvider implements AwsCredentialsProvider {

    @Inject
    Provider<CredentialRequestHandler> credentialRequestHandlerProvider;

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    @Override
    public AwsCredentials resolveCredentials() {
        try {
            AwsCredentials creds = credentialRequestHandlerProvider.get().getAwsCredentials();
            if (creds != null) {
                return creds;
            }
        } catch (Throwable t) {
            throw SdkClientException.create("Failed to fetch credentials", t);
        }
        throw SdkClientException.create("Failed to fetch credentials");
    }
}
