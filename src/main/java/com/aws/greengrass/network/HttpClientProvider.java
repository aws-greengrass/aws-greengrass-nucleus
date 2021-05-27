/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.network;

import com.aws.greengrass.util.ProxyUtils;
import software.amazon.awssdk.http.SdkHttpClient;


public class HttpClientProvider {
    /**
     * Provides a SdkHttpClient with Proxy configuration if it is available or a regular ApacheHttpClient. Invoker
     * should close client properly as this class doesn't close the provided client.
     *
     * @return SdkHttpClient for making http calls
     */
    public SdkHttpClient getSdkHttpClient() {
        return ProxyUtils.getSdkHttpClient();
    }

}
