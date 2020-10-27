/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.aws.greengrass.lifecyclemanager.Kernel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;

import javax.inject.Inject;

import static com.aws.greengrass.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;

public class LazyCredentialProvider implements AWSCredentialsProvider, AwsCredentialsProvider {

    private final Kernel kernel;

    @Inject
    public LazyCredentialProvider(Kernel kernel) {
        this.kernel = kernel;
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    @SuppressFBWarnings("BC_UNCONFIRMED_CAST_OF_RETURN_VALUE")
    @Override
    public AwsCredentials resolveCredentials() {
        try {
            AwsCredentials creds =
                    ((TokenExchangeService) kernel.locate(TOKEN_EXCHANGE_SERVICE_TOPICS)).resolveCredentials();
            if (creds != null) {
                return creds;
            }
        } catch (Throwable t) {
            throw SdkClientException.create("Failed to fetch credentials", t);
        }
        throw SdkClientException.create("Failed to fetch credentials");
    }

    // AWSCredentials is for the V1 AWS SDK. Greengrass SDK is only built with V1 right now.
    // TODO: Get V2 version of Greengrass SDK and then remove this.
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
