/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * This class serves as the core model for recipe metadata and also represents the JSON format for persistence.
 */
@Data
@RequiredArgsConstructor
@NoArgsConstructor  // need for JSON deserialization
public class RecipeMetadata {
    @NonNull String componentVersionArn;

    /**
     * Will set to true when APK for any version of that component was installed.
     * Android specific.
     */
    @NonNull boolean isAPKInstalled;

    public RecipeMetadata(@NonNull String componentVersionArn) {
        this(componentVersionArn, false);
    }
}
