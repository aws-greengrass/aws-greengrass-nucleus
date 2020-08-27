/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.tes;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.aws.iot.evergreen.dependency.Context;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;

import javax.inject.Inject;

public class LazyCredentialProvider implements AWSCredentialsProvider, AwsCredentialsProvider {

    private final Context context;

    @Inject
    public LazyCredentialProvider(Context context) {
        this.context = context;
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    @Override
    public AwsCredentials resolveCredentials() {
        try {
            return context.get(TokenExchangeService.class).resolveCredentials();
        } catch (Throwable t) {
            throw SdkClientException.create("Failed to fetch credentials", t);
        }
    }

    // AWSCredentials is for the V1 AWS SDK. Evergreen SDK is only built with V1 right now.
    // TODO: Get V2 version of Evergreen SDK and then remove this.
    //  (https://sim.amazon.com/issues/1e07a20f-05d2-436d-b562-82c03abbce01)
    @Override
    public AWSCredentials getCredentials() {
        try {
            AwsSessionCredentials credentials = (AwsSessionCredentials) resolveCredentials();
            return new BasicSessionCredentials(credentials.accessKeyId(), credentials.secretAccessKey(),
                    credentials.sessionToken());
        } catch (SdkClientException e) {
            throw new AmazonClientException(e);
        }
    }

    @Override
    public void refresh() {
    }
}
