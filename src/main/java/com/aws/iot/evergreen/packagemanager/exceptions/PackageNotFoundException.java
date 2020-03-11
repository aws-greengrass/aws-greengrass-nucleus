/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.exceptions;

public class PackageNotFoundException extends Exception {

    public PackageNotFoundException(String message) {
        super(message);
    }
}
