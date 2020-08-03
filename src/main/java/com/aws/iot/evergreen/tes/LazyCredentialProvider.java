/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.tes;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.aws.iot.evergreen.dependency.Context;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import javax.inject.Inject;

public class LazyCredentialProvider implements AWSCredentialsProvider, AwsCredentialsProvider {

    private final Context context;

    @Inject
    public LazyCredentialProvider(Context context) {
        this.context = context;
    }

    @Override
    public AwsCredentials resolveCredentials() {
        return context.get(TokenExchangeService.class).resolveCredentials();
    }

    // AWSCredentials is for the V1 AWS SDK. Evergreen SDK is only built with V1 right now.
    // TODO: Get V2 version of Evergreen SDK and then remove this.
    //  (https://sim.amazon.com/issues/1e07a20f-05d2-436d-b562-82c03abbce01)
    @Override
    public AWSCredentials getCredentials() {
        AwsSessionCredentials credentials = (AwsSessionCredentials) resolveCredentials();
        return new BasicSessionCredentials(credentials.accessKeyId(), credentials.secretAccessKey(),
                credentials.sessionToken());
    }

    @Override
    public void refresh() {
    }
}
