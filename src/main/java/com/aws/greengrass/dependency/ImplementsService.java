/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import javax.annotation.Nonnull;

@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ImplementsService {
    /**
     * The name of the service (must be unique).
     */
    @Nonnull
    String name(); // the name of the service

    /**
     * True if the service should start immediately when Kernel starts.
     */
    boolean autostart() default false;

    /**
     * Version of the service. By default it is 0.0.0. Must be in the form of a.b.c.
     */
    @Nonnull
    String version() default "0.0.0";
}
