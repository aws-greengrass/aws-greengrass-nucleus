/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.security;

import com.aws.greengrass.security.exceptions.KeyLoadingException;
import com.aws.greengrass.security.exceptions.ServiceUnavailableException;

import javax.net.ssl.KeyManager;

public interface CryptoKeySpi {

    KeyManager[] getKeyManagers(String keyUri) throws ServiceUnavailableException, KeyLoadingException;

    String supportedKeyType();
}
