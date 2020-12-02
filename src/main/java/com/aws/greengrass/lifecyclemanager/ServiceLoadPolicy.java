/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

/**
 * The policy to handle exceptions in loading service dependencies.
 */
public enum ServiceLoadPolicy {
    /**
     * Surface the dependency loading exceptions.
     */
    SURFACE_DEPENDENCY_ERROR,

    /**
     * Ignore the dependency loading exceptions and proceed.
     */
    IGNORE_DEPENDENCY_ERROR
}
