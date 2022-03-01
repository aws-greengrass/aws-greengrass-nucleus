/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

/**
 * Interface provided by Android Service layer.
 */
public interface AndroidServiceLevelAPI {
    /**
     * Terminates service.
     *
     * @param status exit status
     */
    public void terminate(int status);
}
