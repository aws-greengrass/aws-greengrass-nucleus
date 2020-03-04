/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

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
    @Nonnull String name();  // the name of the service

    /**
     * True if the service should start immediately when Kernel starts.
     */
    boolean autostart() default false;
}
