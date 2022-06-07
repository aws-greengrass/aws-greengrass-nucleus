/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


// FIXME: android: add implementation for Android tag: fastclasspathscanner
//  see https://klika-tech.atlassian.net/browse/GGSA-62

package com.aws.greengrass.dependency.android;


@FunctionalInterface
public interface ClassAnnotationMatchProcessor {

    void processMatch(Class<?> classWithAnnotation);
}
