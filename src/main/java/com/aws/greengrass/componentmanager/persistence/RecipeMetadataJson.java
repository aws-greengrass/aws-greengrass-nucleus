/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.persistence;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * This class represents the JSON file format for Recipe's Metadata when persisting.
 */
@JsonSerialize
@JsonDeserialize
@Data
@RequiredArgsConstructor
@NoArgsConstructor
public class RecipeMetadataJson {
    @NonNull String componentArn;
}
